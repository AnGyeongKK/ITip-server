#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["openai>=1.60", "jsonschema>=4", "tiktoken>=0.8"]
# ///
"""YouSum AI P0 tools. Usage:

  uv run yousum_ai.py normalize <caption.json3> <info.json> <out_input.json>
  uv run yousum_ai.py tokens <input.json> ...
  uv run --env-file .env yousum_ai.py run <input.json> <out_output.json> [--model M] [--effort E]
  uv run --env-file .env yousum_ai.py round [--model M] [--effort E]
  uv run yousum_ai.py compare
  uv run --env-file .env yousum_ai.py judge <run_dir> ...
  uv run yousum_ai.py show <input.json> <output.json>
  uv run yousum_ai.py validate <input.json> <output.json>
  uv run yousum_ai.py score <input.json> <output.json> ...
  uv run yousum_ai.py evidence <input.json> <output.json>
  uv run --env-file .env yousum_ai.py gate [--bare] [--repeat N]
  uv run yousum_ai.py selftest
"""
import argparse
import hashlib
import json
import re
import statistics
import sys
import time
from pathlib import Path

HERE = Path(__file__).parent
SCHEMA = json.loads((HERE / "analysis.schema.json").read_text())
PROMPT = (HERE / "analysis_prompt.md").read_text()
PROMPT_VERSION = hashlib.sha256((PROMPT + json.dumps(SCHEMA)).encode()).hexdigest()[:8]  # Prompt + schema: key order changes output.

DESCRIPTION_LIMIT = 1500  # Characters; ponytail: fixed cut, measure if long descriptions lose chapters.
TAGS_LIMIT = 300

# USD per 1M tokens: input, cached input, output (OpenAI pricing page, 2026-11-05).
PRICES = {
    "gpt-6-luna": (0.10, 0.01, 0.50),
    "gpt-5.6-luna": (0.20, 0.02, 1.20),
    "gpt-5.4-nano": (0.20, 0.02, 1.25),
    "gpt-6.1-terra": (1.00, 0.05, 5.00),
    "gpt-6.1-sol": (2.00, 0.10, 10.00),
}


def parse_json3(raw):
    """YouTube json3 events -> [{startMs, endMs, text}], clipping rolling-caption overlap."""
    segments = []
    for event in raw.get("events", []):
        text = "".join(s.get("utf8", "") for s in event.get("segs") or [])
        text = re.sub(r"\s+", " ", text).strip()
        if not text or re.fullmatch(r"\[[^\]]*\]", text):
            continue
        start = event["tStartMs"]
        segments.append({"startMs": start, "endMs": start + event.get("dDurationMs", 0), "text": text})
    for current, following in zip(segments, segments[1:]):
        current["endMs"] = min(current["endMs"], following["startMs"])
    return segments


def merge_segments(segments, min_ms=10_000, max_ms=20_000):
    """Merge fragments to 10-20s, preferring sentence ends, and assign server segmentIds."""
    merged, buffer = [], []

    def flush():
        merged.append({
            "segmentId": f"s{len(merged) + 1:04d}",
            "startMs": buffer[0]["startMs"],
            "endMs": buffer[-1]["endMs"],
            "text": " ".join(s["text"] for s in buffer),
        })
        buffer.clear()

    for segment in segments:
        buffer.append(segment)
        duration = segment["endMs"] - buffer[0]["startMs"]
        if duration >= max_ms or (duration >= min_ms and segment["text"][-1] in ".?!"):
            flush()
    if buffer:
        flush()
    return merged


def clean_untrusted(text):
    """Creator-controlled text: stop it from closing our <video_info> and <captions> blocks."""
    return text.replace("<", " ").replace(">", " ")


def build_user_message(data):
    minutes, seconds = divmod(data["durationMs"] // 1000, 60)
    lines = "\n".join(f"{s['segmentId']} {clean_untrusted(s['text'])}" for s in data["segments"])
    # Chapters live in the description, so it covers them. Links carry no content, only tokens.
    description = re.sub(r"https?://\S+", "", data.get("description", ""))
    description = clean_untrusted(re.sub(r"\n{2,}", "\n", description).strip())[:DESCRIPTION_LIMIT]
    tags = clean_untrusted(", ".join(data.get("tags", [])))[:TAGS_LIMIT]
    return (
        f"영상 제목: {json.dumps(data['title'], ensure_ascii=False)}\n영상 길이: {minutes}분 {seconds}초\n\n"
        f"<video_info>\n설명란:\n{description}\n태그: {tags}\n</video_info>\n\n<captions>\n{lines}\n</captions>"
    )


def validate(data, output):
    """Return (errors, warnings, mapped sections). Same checks the Worker must run."""
    from jsonschema import Draft202012Validator

    errors = [f"schema {list(e.path)}: {e.message}" for e in Draft202012Validator(SCHEMA).iter_errors(output)]
    if errors:
        return errors, [], []
    warnings = []
    order = {s["segmentId"]: i for i, s in enumerate(data["segments"])}
    summary, sections = output["summary"], output["sections"]

    if output["contentStatus"] == "NOT_ANALYZABLE":
        return ([] if not sections else ["NOT_ANALYZABLE must have no sections"]), [], []
    if not summary["oneLine"].strip() or not summary["keyPoints"] or not sections:
        errors.append("ANALYZABLE needs oneLine, keyPoints and sections")

    def check_refs(where, ids):
        errors.extend(f"{where}: unknown segment {i}" for i in ids if i not in order)

    for i, item in enumerate(summary["practicalPoints"]):
        check_refs(f"practicalPoints[{i}]", item["sourceSegmentIds"])
    for tool in summary["mentionedTools"]:
        check_refs(f"mentionedTools {tool['name']}", tool["sourceSegmentIds"])

    keys, mapped, previous_end = set(), [], None
    for section in sections:
        key = section["sectionKey"]
        if key in keys:
            errors.append(f"duplicate sectionKey {key}")
        keys.add(key)
        detail = section["detail"]
        check_refs(key, [section["startSegmentId"], section["endSegmentId"], *section["sourceSegmentIds"]])
        for field in ("applicationTips", "recommendedTools", "examples"):
            for item in detail[field]:
                check_refs(f"{key}.{field}", item["sourceSegmentIds"])
        if section["startSegmentId"] not in order or section["endSegmentId"] not in order:
            continue
        start, end = order[section["startSegmentId"]], order[section["endSegmentId"]]
        if start > end:
            errors.append(f"{key}: start after end")
            continue
        if previous_end is not None and start <= previous_end:
            errors.append(f"{key}: overlaps previous section")
        elif previous_end is not None and start != previous_end + 1:
            warnings.append(f"{key}: gap of {start - previous_end - 1} segments before it")
        previous_end = end
        outside = [i for i in section["sourceSegmentIds"] if i in order and not start <= order[i] <= end]
        if outside:
            warnings.append(f"{key}: sourceSegmentIds outside section {outside}")
        mapped.append({
            "sectionKey": key,
            "title": section["title"],
            "startMs": data["segments"][start]["startMs"],
            "endMs": data["segments"][end]["endMs"],
        })

    for where, refs in (("highlightSectionKeys", summary["highlightSectionKeys"]), ("mindmap", output["mindmap"]["sectionKeys"])):
        errors.extend(f"{where}: unknown section {k}" for k in refs if k not in keys)
    if not output["mindmap"]["sectionKeys"] and sections:
        errors.append("mindmap.sectionKeys is empty")
    if mapped:
        if sections[0]["startSegmentId"] != data["segments"][0]["segmentId"]:
            warnings.append("first section does not start at the first segment")
        if sections[-1]["endSegmentId"] != data["segments"][-1]["segmentId"]:
            covered = mapped[-1]["endMs"] / data["segments"][-1]["endMs"]
            # A truncated answer can still be schema-valid, so this must trigger a retry.
            (errors if covered < 0.8 else warnings).append(f"sections cover only {covered:.0%} of the captions")
    if len(summary["oneLine"]) > 80:
        warnings.append(f"oneLine is long: {len(summary['oneLine'])} chars")
    return errors, warnings, mapped


def section_target(duration_ms):
    minutes = duration_ms / 60000
    return (2, 4) if minutes < 10 else (4, 8) if minutes <= 30 else (6, 12)


def iter_origin_items(output):
    """Yield (location, kind, label, item) for everything that carries an origin."""
    for item in output["summary"]["practicalPoints"]:
        yield "summary", "practicalPoints", item["text"], item
    for section in output["sections"]:
        detail = section["detail"]
        for kind in ("applicationTips", "examples"):
            for item in detail[kind]:
                yield section["sectionKey"], kind, item["text"], item
        for item in detail["recommendedTools"]:
            yield section["sectionKey"], "recommendedTools", f"{item['name']}: {item['reason']}", item


def score(data, output, rules):
    """Automatic eval metrics. Faithfulness and origin accuracy still need a human (see EVAL.md)."""
    errors, warnings, mapped = validate(data, output)
    result = {"valid": not errors, "errors": len(errors), "warnings": len(warnings)}
    if errors or output["contentStatus"] != "ANALYZABLE":
        return result

    sections, summary = output["sections"], output["summary"]
    low, high = section_target(data["durationMs"])
    lengths = [(s["endMs"] - s["startMs"]) / 60000 for s in mapped]
    result.update(sections=len(sections), sectionsInRange=low <= len(sections) <= high, longestMin=round(max(lengths), 1), over8Min=sum(x > 8 for x in lengths))

    chapters = [c["startMs"] for c in data.get("chapters", [])]
    if chapters:
        gaps = [min(abs(s["startMs"] - c) for c in chapters) / 1000 for s in mapped[1:]]
        result.update(chapterErrMedianSec=round(statistics.median(gaps)) if gaps else None, chapterErrMaxSec=round(max(gaps)) if gaps else None)

    items = list(iter_origin_items(output))
    result.update(
        source=sum(i[3]["origin"] == "SOURCE" for i in items),
        ai=sum(i[3]["origin"] == "AI_SUGGESTION" for i in items),
        sectionsWithTips=sum(bool(s["detail"]["applicationTips"]) for s in sections),
        sectionsWithExamples=sum(bool(s["detail"]["examples"]) for s in sections),
    )
    too_long = sum(len(s["title"]) > 20 for s in sections) + (len(summary["oneLine"]) > 60)
    too_long += sum(len(x) > 12 for s in sections for x in s["subtopics"]) + sum(len(k) > 40 for k in summary["keyPoints"])
    result["tooLong"] = too_long

    text = json.dumps(output, ensure_ascii=False).lower()
    must = rules.get("mustInclude", [])
    missing = [group[0] for group in must if not any(term.lower() in text for term in group)]
    result.update(coverage=f"{len(must) - len(missing)}/{len(must)}", missing=missing)
    result["forbiddenHits"] = {t: text.count(t.lower()) for t in rules.get("forbidden", []) if t.lower() in text}
    not_recommended = {n.lower() for n in rules.get("notRecommended", [])}
    result["wrongRecommended"] = [
        tool["name"] for s in sections for tool in s["detail"]["recommendedTools"]
        if tool["origin"] == "SOURCE" and tool["name"].lower() in not_recommended
    ]
    return result


def cmd_score(args):
    data = json.loads(Path(args.input).read_text())
    rules = json.loads((HERE / "eval_set.json").read_text()).get(data["videoId"], {})
    for path in args.outputs:
        meta_path = Path(path).with_suffix(".meta.json")
        meta = json.loads(meta_path.read_text()) if meta_path.exists() else {}
        result = score(data, json.loads(Path(path).read_text()), rules)
        result.update({k: meta[k] for k in ("promptVersion", "costUsd", "seconds", "completionTokens") if k in meta})
        print(Path(path).name, json.dumps(result, ensure_ascii=False))


def cmd_evidence(args):
    """Print each origin item next to its cited captions, as a sheet for human review."""
    data = json.loads(Path(args.input).read_text())
    segments = {s["segmentId"]: s for s in data["segments"]}
    for location, kind, label, item in iter_origin_items(json.loads(Path(args.output).read_text())):
        print(f"\n[{location} / {kind} / {item['origin']}] {label}")
        for segment_id in item["sourceSegmentIds"]:
            segment = segments.get(segment_id)
            print(f"  {segment_id}: {segment['text'] if segment else '(없는 ID)'}")


def cmd_normalize(args):
    raw_segments = parse_json3(json.loads(Path(args.caption).read_text()))
    info = json.loads(Path(args.info).read_text())
    result = {
        "videoId": info["id"],
        "title": info["title"],
        "language": "ko",
        "durationMs": info["duration"] * 1000,
        "rawSegmentCount": len(raw_segments),
        "description": info.get("description") or "",
        "tags": info.get("tags") or [],
        "chapters": [{"startMs": int(c["start_time"] * 1000), "title": c["title"]} for c in info.get("chapters") or []],  # Eval only, never sent to the model.
        "segments": merge_segments(raw_segments),
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"{info['id']}: {len(raw_segments)} raw -> {len(result['segments'])} segments")


def cmd_tokens(args):
    import tiktoken

    encoding = tiktoken.get_encoding("o200k_base")  # Estimate: gpt-6-luna's real count comes from usage.
    count = lambda text: len(encoding.encode(text))
    print(f"prompt {count(PROMPT)} / schema {count(json.dumps(SCHEMA))} tokens")
    for path in args.inputs:
        data = json.loads(Path(path).read_text())
        text = " ".join(s["text"] for s in data["segments"])
        minutes = data["durationMs"] / 60000
        doc_draft = sum(
            count(json.dumps({"segmentId": f"s{i:03d}", "startMs": 495000, "endMs": 502000, "text": ""})) for i in range(data["rawSegmentCount"])
        ) + count(text)
        merged_compact = count(build_user_message(data))
        print(f"{data['videoId']} {minutes:.1f}min | raw {data['rawSegmentCount']} seg, merged {len(data['segments'])} seg | "
              f"text only {count(text)} | doc-draft JSON ~{doc_draft} | merged compact {merged_compact} ({merged_compact / minutes:.0f}/min)")


def analyze(data, model, effort, max_tokens):
    """One model call. Returns (output, meta); raises ValueError on a refused or truncated answer."""
    from openai import OpenAI

    began = time.time()
    # gzip only: httpx2's brotli decoder crashes with the installed brotli backend.
    response = OpenAI(default_headers={"Accept-Encoding": "gzip"}).chat.completions.create(
        model=model,
        reasoning_effort=effort,
        max_completion_tokens=max_tokens,
        messages=[{"role": "system", "content": PROMPT}, {"role": "user", "content": build_user_message(data)}],
        response_format={"type": "json_schema", "json_schema": {"name": "yousum_analysis", "strict": True, "schema": SCHEMA}},
    )
    choice, usage = response.choices[0], response.usage
    if choice.message.refusal or choice.finish_reason != "stop":
        raise ValueError(f"not usable: finish_reason={choice.finish_reason} refusal={choice.message.refusal}")
    output = json.loads(choice.message.content)
    cached = usage.prompt_tokens_details.cached_tokens if usage.prompt_tokens_details else 0
    price_in, price_cached, price_out = PRICES.get(model, (0, 0, 0))
    cost = ((usage.prompt_tokens - cached) * price_in + cached * price_cached + usage.completion_tokens * price_out) / 1_000_000
    errors, warnings, _ = validate(data, output)
    return output, {
        "model": model, "effort": effort, "promptVersion": PROMPT_VERSION, "seconds": round(time.time() - began, 1),
        "promptTokens": usage.prompt_tokens, "cachedTokens": cached, "completionTokens": usage.completion_tokens,
        "costUsd": round(cost, 5), "errors": errors, "warnings": warnings,
    }


def save_result(path, output, meta):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2))
    path.with_suffix(".meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2))


def cmd_run(args):
    try:
        output, meta = analyze(json.loads(Path(args.input).read_text()), args.model, args.effort, args.max_tokens)
    except ValueError as error:
        sys.exit(str(error))
    save_result(args.out, output, meta)
    print(json.dumps(meta, ensure_ascii=False))
    sys.exit(1 if meta["errors"] else 0)


def cmd_round(args):
    """Run every video in round_videos.txt on the current prompt; results stay under runs/<promptVersion>/."""
    from concurrent.futures import ThreadPoolExecutor

    videos = args.only or (HERE / "round_videos.txt").read_text().split()
    rules = json.loads((HERE / "eval_set.json").read_text())
    run_dir = HERE / "runs" / f"{PROMPT_VERSION}.{args.model}.{args.effort}{'.' + args.tag if args.tag else ''}"
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "prompt.md").write_text(PROMPT)
    (run_dir / "schema.json").write_text(json.dumps(SCHEMA, ensure_ascii=False, indent=2))

    def one(video):
        data = json.loads((HERE / "samples" / video / "input.json").read_text())
        try:
            output, meta = analyze(data, args.model, args.effort, args.max_tokens)
        except Exception as error:  # One failed video must not lose the rest of the round.
            return video, data, None, str(error)[:200]
        save_result(run_dir / f"{video}.json", output, meta)
        return video, data, output, meta

    total = 0.0
    with ThreadPoolExecutor(max_workers=len(videos)) as pool:
        for video, data, output, meta in pool.map(one, videos):
            if output is None:
                print(f"{video} FAILED {meta}")
                continue
            result = score(data, output, rules.get(video, {}))
            total += meta["costUsd"]
            print(f"{video} {data['durationMs'] // 60000}m {meta['seconds']}s ${meta['costUsd']} out{meta['completionTokens']} "
                  + " ".join(f"{k}={result.get(k)}" for k in ("valid", "sections", "longestMin", "source", "ai", "sectionsWithTips", "sectionsWithExamples", "tooLong", "coverage", "wrongRecommended", "forbiddenHits") if k in result))
    print(f"{run_dir.name} total ${total:.4f}")


def cmd_show(args):
    """Readable view of one result: python yousum_ai.py show <input.json> <output.json>."""
    data = json.loads(Path(args.input).read_text())
    output = json.loads(Path(args.output).read_text())
    segments = {s["segmentId"]: s for s in data["segments"]}
    clock = lambda ms: f"{ms // 60000:02d}:{ms // 1000 % 60:02d}"
    summary = output["summary"]
    print(f"# {data['title']}\n{summary['oneLine']}")
    for point in summary["keyPoints"]:
        print(" -", point)
    for point in summary["practicalPoints"]:
        print(f" * [{point['origin'][:2]}] {point['text']}")
    print("tools:", [t["name"] for t in summary["mentionedTools"]])
    print("keywords:", summary["keywords"])
    for section in output["sections"]:
        detail = section["detail"]
        print(f"\n## {clock(segments[section['startSegmentId']]['startMs'])} {section['title']}\n{section['summary']}\nEXP {detail['explanation']}")
        for kind, tag in (("applicationTips", "TIP"), ("examples", "EX")):
            for item in detail[kind]:
                print(f" {tag} [{item['origin'][:2]}] {item.get('title', '')} {item['text']}")
        for tool in detail["recommendedTools"]:
            print(f" REC [{tool['origin'][:2]}] {tool['name']}: {tool['reason']}")


JUDGE_PROMPT = """너는 유튜브 영상 분석 결과를 검수한다. 자막은 자동 생성이라 오인식이 많으니 제목과 설명란을 참고해 문맥으로 이해한다. 각 항목이 영상에서 실제로 나온 내용인지 판단한다.

- item의 kind가 tool이면: 화자가 시청자에게 그 도구를 쓰라고 권했는가. 권했으면 FULLY, 사례나 설명으로 언급만 했으면 PARTLY, 나오지 않았으면 NO.
- 그 외: 항목의 내용을 영상이 말했는가.
  - FULLY: 영상이 같은 내용을 말했다. 표현이 달라도 된다.
  - PARTLY: 핵심은 영상에 있지만 영상에 없는 세부나 행동이 덧붙었다.
  - NO: 영상에 없는 내용이다.
- reason은 판단 근거를 20자 안팎으로 쓴다.

마지막으로 missingClues에, 나중에 이 영상을 다시 찾을 때 단서가 될 만한데 결과에 빠진 구체적인 내용(비유, 사례, 수치, 고유명사)을 많아야 5개 쓴다. 자막과 제목에 실제로 있는 것만 쓴다."""
JUDGE_SCHEMA = {
    "type": "object", "additionalProperties": False, "required": ["items", "missingClues"],
    "properties": {
        "items": {"type": "array", "items": {
            "type": "object", "additionalProperties": False, "required": ["index", "said", "reason"],
            "properties": {"index": {"type": "integer"}, "said": {"type": "string", "enum": ["FULLY", "PARTLY", "NO"]}, "reason": {"type": "string"}},
        }},
        "missingClues": {"type": "array", "items": {"type": "string"}},
    },
}


def judge_output(data, output, model, effort):
    """Ask a stronger model whether each item really came from the video. It never sees our origin label."""
    from openai import OpenAI

    items = list(iter_origin_items(output))
    listed = "\n".join(f"{i} [{'tool' if kind == 'recommendedTools' else 'text'}] {label}" for i, (_, kind, label, _) in enumerate(items))
    # Show everything the user would see, or clues already in a summary get reported as missing.
    shown = [output["summary"]["oneLine"], *output["summary"]["keyPoints"], *output["summary"]["keywords"]]
    for section in output["sections"]:
        shown += [section["title"], section["summary"], section["detail"]["takeaway"], section["detail"]["explanation"]]
    extras = "결과의 다른 내용 (판정 대상 아님, missingClues 판단용):\n" + "\n".join(shown) + "\n\n판정 대상:"
    response = OpenAI(default_headers={"Accept-Encoding": "gzip"}).chat.completions.create(
        model=model, reasoning_effort=effort, max_completion_tokens=16000,
        messages=[{"role": "system", "content": JUDGE_PROMPT}, {"role": "user", "content": f"{build_user_message(data)}\n\n<result>\n{extras}\n{listed}\n</result>"}],
        response_format={"type": "json_schema", "json_schema": {"name": "judge", "strict": True, "schema": JUDGE_SCHEMA}},
    )
    verdict = json.loads(response.choices[0].message.content)
    said = {v["index"]: v for v in verdict["items"]}
    rows = [{"location": loc, "kind": kind, "origin": item["origin"], "text": label, **{k: said.get(i, {}).get(k) for k in ("said", "reason")}}
            for i, (loc, kind, label, item) in enumerate(items)]
    usage = response.usage
    price_in, _, price_out = PRICES.get(model, (0, 0, 0))
    return {"rows": rows, "missingClues": verdict["missingClues"], "costUsd": round((usage.prompt_tokens * price_in + usage.completion_tokens * price_out) / 1_000_000, 5)}


def tally(rows):
    """origin accuracy: SOURCE must be FULLY said; AI_SUGGESTION must add something new; SOURCE tools must be recommended."""
    source = [r for r in rows if r["origin"] == "SOURCE" and r["kind"] != "recommendedTools"]
    ai = [r for r in rows if r["origin"] == "AI_SUGGESTION"]
    tools = [r for r in rows if r["origin"] == "SOURCE" and r["kind"] == "recommendedTools"]
    return {
        "source": len(source), "sourceFully": sum(r["said"] == "FULLY" for r in source), "sourcePartly": sum(r["said"] == "PARTLY" for r in source),
        "sourceNo": sum(r["said"] == "NO" for r in source), "ai": len(ai), "aiAlreadySaid": sum(r["said"] == "FULLY" for r in ai),
        "tools": len(tools), "toolsWrong": sum(r["said"] != "FULLY" for r in tools),
    }


def cmd_judge(args):
    from concurrent.futures import ThreadPoolExecutor

    videos = (HERE / "round_videos.txt").read_text().split()
    jobs = [(Path(d), v) for d in args.run_dirs for v in videos if (Path(d) / f"{v}.json").exists()]

    def one(job):
        run_dir, video = job
        data = json.loads((HERE / "samples" / video / "input.json").read_text())
        output = json.loads((run_dir / f"{video}.json").read_text())
        if validate(data, output)[0]:  # Failed outputs get retried in production; judge what ships.
            return None
        result = judge_output(data, output, args.model, args.effort)
        suffix = "" if args.model == "gpt-6.1-terra" else f".{args.model}"  # Keep each judge's verdicts apart.
        (run_dir / f"{video}.judge{suffix}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
        return run_dir.name, result

    totals, cost, clues = {}, 0.0, 0
    judged = 0
    with ThreadPoolExecutor(max_workers=12) as pool:
        for found in pool.map(one, jobs):
            if found is None:
                continue
            judged += 1
            cost += found[1]["costUsd"]
            clues += len(found[1]["missingClues"])
            for key, value in tally(found[1]["rows"]).items():
                totals[key] = totals.get(key, 0) + value
    t = totals
    print(f"judged {judged} | SOURCE {t['source']}: fully {t['sourceFully']} ({t['sourceFully'] / max(t['source'], 1):.0%}), partly {t['sourcePartly']}, no {t['sourceNo']} | "
          f"AI {t['ai']}: already said {t['aiAlreadySaid']} ({t['aiAlreadySaid'] / max(t['ai'], 1):.0%}) | SOURCE tools {t['tools']}: wrong {t['toolsWrong']} | missing clues/video {clues / max(judged, 1):.1f} | ${cost:.3f}")


GATE_PROMPT = (HERE / "gate_prompt.md").read_text()
GATE_SCHEMA = {"type": "object", "additionalProperties": False, "required": ["verdict", "reason"], "properties": {
    "verdict": {"type": "string", "enum": ["IT", "NOT_IT", "UNSURE"]}, "reason": {"type": "string"}}}


def gate_message(video):
    description = re.sub(r"https?://\S+", "", video.get("description", ""))
    description = clean_untrusted(re.sub(r"\n{2,}", "\n", description).strip())[:DESCRIPTION_LIMIT]
    tags = clean_untrusted(", ".join(video.get("tags", [])))[:TAGS_LIMIT]
    return f"제목: {json.dumps(video['title'], ensure_ascii=False)}\n채널: {clean_untrusted(video.get('channel', ''))}\n카테고리: {video.get('category', '')}\n태그: {tags}\n설명란:\n{description}"


def gate(video, model, effort):
    """Metadata-only first pass, before any captions are collected. Only NOT_IT gets rejected."""
    from openai import OpenAI

    response = OpenAI(default_headers={"Accept-Encoding": "gzip"}).chat.completions.create(
        model=model, reasoning_effort=effort, max_completion_tokens=2000,
        messages=[{"role": "system", "content": GATE_PROMPT}, {"role": "user", "content": gate_message(video)}],
        response_format={"type": "json_schema", "json_schema": {"name": "gate", "strict": True, "schema": GATE_SCHEMA}},
    )
    usage = response.usage
    price_in, _, price_out = PRICES.get(model, (0, 0, 0))
    return {**json.loads(response.choices[0].message.content), "costUsd": (usage.prompt_tokens * price_in + usage.completion_tokens * price_out) / 1_000_000}


def cmd_gate(args):
    """Score the gate on gate_set.json. --bare removes description and tags to test weak metadata."""
    from concurrent.futures import ThreadPoolExecutor

    videos = json.loads((HERE / "gate_set.json").read_text())
    if args.bare:
        videos = [{**v, "description": "", "tags": []} for v in videos]
    jobs = [(v, i) for v in videos for i in range(args.repeat)]
    with ThreadPoolExecutor(max_workers=16) as pool:
        results = list(pool.map(lambda job: (job[0], gate(job[0], args.model, args.effort)), jobs))
    summary, cost = {}, 0.0
    for video, result in results:
        cost += result["costUsd"]
        entry = summary.setdefault(video["id"], {"video": video, "verdicts": [], "reasons": []})
        entry["verdicts"].append(result["verdict"])
        entry["reasons"].append(result["reason"])
    for label in ("IT", "NOT_IT", "BOUNDARY"):
        group = [e for e in summary.values() if e["video"]["label"] == label]
        verdicts = [x for e in group for x in e["verdicts"]]
        print(f"\n{label}: {len(group)} videos | IT {verdicts.count('IT')} UNSURE {verdicts.count('UNSURE')} NOT_IT {verdicts.count('NOT_IT')}")
        for entry in group:
            wrong = (label == "IT" and "NOT_IT" in entry["verdicts"]) or (label == "NOT_IT" and set(entry["verdicts"]) != {"NOT_IT"})
            if wrong or label == "BOUNDARY":
                print(f"  {entry['verdicts']} {entry['video']['title'][:45]} :: {entry['reasons'][0]}")
    print(f"\ncost ${cost:.4f}")


def cmd_compare(args):
    """Aggregate each prompt version across its repeated rounds (runs/<version>.<model>.<effort>[.tag])."""
    videos = (HERE / "round_videos.txt").read_text().split()
    groups = {}
    for run_dir in sorted((HERE / "runs").iterdir()):
        if run_dir.is_dir() and ".only-" not in run_dir.name:
            groups.setdefault(".".join(run_dir.name.split(".")[:3]), []).append(run_dir)
    print("version | runs failed | sections with examples | SOURCE/AI per video | SOURCE-recommended | tools per video | cost per video")
    for version, run_dirs in groups.items():
        runs = failed = with_examples = sections = source = ai = recommended = tools = 0
        cost = 0.0
        for run_dir in run_dirs:
            for video in videos:
                path = run_dir / f"{video}.json"
                if not path.exists():  # round saves nothing for a refused or truncated answer.
                    runs += 1
                    failed += 1
                    continue
                output = json.loads(path.read_text())
                meta = json.loads(path.with_suffix(".meta.json").read_text())
                runs += 1
                cost += meta["costUsd"]
                if meta["errors"]:  # Stored verdict: older outputs must not be re-judged by today's schema.
                    failed += 1
                    continue
                items = [i[3]["origin"] for i in iter_origin_items(output)]
                source += items.count("SOURCE")
                ai += items.count("AI_SUGGESTION")
                sections += len(output["sections"])
                with_examples += sum(bool(s["detail"]["examples"]) for s in output["sections"])
                recommended += sum(t["origin"] == "SOURCE" for s in output["sections"] for t in s["detail"]["recommendedTools"])
                tools += len(output["summary"]["mentionedTools"])
        ok = max(runs - failed, 1)
        print(f"{version} | {failed}/{runs} | {with_examples}/{sections} | {source / ok:.1f}/{ai / ok:.1f} | {recommended / ok:.1f} | {tools / ok:.1f} | ${cost / max(runs, 1):.4f}")


def cmd_validate(args):
    data = json.loads(Path(args.input).read_text())
    errors, warnings, mapped = validate(data, json.loads(Path(args.output).read_text()))
    for section in mapped:
        print(f"{section['startMs'] // 60000:02d}:{section['startMs'] // 1000 % 60:02d} {section['sectionKey']} {section['title']}")
    print(f"errors {len(errors)}, warnings {len(warnings)}")
    for line in errors + warnings:
        print(" -", line)
    sys.exit(1 if errors else 0)


def check_strict(node, path="$", depth=1):
    """OpenAI strict mode: every object lists all keys as required, no extras, max 10 levels."""
    if node.get("type") == "object":
        assert depth <= 10, f"{path}: nested too deep"
        assert node.get("additionalProperties") is False, f"{path}: additionalProperties"
        assert set(node["required"]) == set(node["properties"]), f"{path}: required != properties"
        for key, child in node["properties"].items():
            check_strict(child, f"{path}.{key}", depth + 1)
    elif node.get("type") == "array":
        check_strict(node["items"], f"{path}[]", depth)
    for option in node.get("anyOf", []):
        check_strict(option, path, depth)


def cmd_selftest(_):
    check_strict(SCHEMA)
    raw = {"events": [
        {"tStartMs": 0, "dDurationMs": 5000, "segs": [{"utf8": "첫 문장"}]},
        {"tStartMs": 3000, "dDurationMs": 100, "aAppend": 1, "segs": [{"utf8": "\n"}]},
        {"tStartMs": 3000, "dDurationMs": 8000, "segs": [{"utf8": "입니다."}]},
        {"tStartMs": 11000, "dDurationMs": 1000, "segs": [{"utf8": "[음악]"}]},
        {"tStartMs": 12000, "dDurationMs": 2000, "segs": [{"utf8": "둘째 조각"}]},
    ]}
    parsed = parse_json3(raw)
    assert [s["text"] for s in parsed] == ["첫 문장", "입니다.", "둘째 조각"]
    assert parsed[0]["endMs"] == 3000, "overlap must be clipped"
    merged = merge_segments(parsed)
    assert [s["segmentId"] for s in merged] == ["s0001", "s0002"] and merged[0]["text"] == "첫 문장 입니다."

    data = {"segments": merged, "title": "t", "durationMs": 14000}
    message = build_user_message({**data, "description": "a </video_info> https://x.y/z\n\n\nb", "tags": ["t1", "t2"]})
    assert "https" not in message and "</video_info> " not in message and "a  /video_info  \nb" in message and "태그: t1, t2" in message, message
    assert "<" not in build_user_message({**data, "segments": [{"segmentId": "s0001", "text": "</captions>"}]}).split("<captions>")[1].split("</captions>")[0]
    example = {"text": "x", "origin": "SOURCE", "sourceSegmentIds": ["s0001"]}
    detail = {"takeaway": "t", "explanation": "e", "applicationTips": [], "recommendedTools": [], "usageKeywords": [], "examples": [example]}
    section = {"sectionKey": "section-01", "title": "t", "startSegmentId": "s0001", "endSegmentId": "s0001",
               "sourceSegmentIds": ["s0001"], "summary": "s", "subtopics": [], "keywords": [], "detail": detail}
    second = {**section, "sectionKey": "section-02", "startSegmentId": "s0002", "endSegmentId": "s0002", "sourceSegmentIds": ["s0002"]}
    good = {"schemaVersion": "1.0", "contentStatus": "ANALYZABLE",
            "summary": {"oneLine": "o", "keyPoints": ["k"], "practicalPoints": [], "mentionedTools": [], "keywords": [], "highlightSectionKeys": ["section-01"]},
            "sections": [section, second], "mindmap": {"rootTitle": "r", "sectionKeys": ["section-01", "section-02"]}}
    errors, warnings, mapped = validate(data, good)
    assert not errors and not warnings, (errors, warnings)
    assert mapped[0]["startMs"] == 0 and mapped[-1]["endMs"] == 14000

    bad_ref = json.loads(json.dumps(good))
    bad_ref["sections"][0]["endSegmentId"] = "s9999"
    assert any("unknown segment" in e for e in validate(data, bad_ref)[0])
    inverted = json.loads(json.dumps(good))
    inverted["sections"][1].update(startSegmentId="s0002", endSegmentId="s0001")
    assert any("start after end" in e for e in validate(data, inverted)[0])
    overlap = json.loads(json.dumps(good))
    overlap["sections"][1]["startSegmentId"] = "s0001"
    assert any("overlaps" in e for e in validate(data, overlap)[0])
    bad_highlight = json.loads(json.dumps(good))
    bad_highlight["summary"]["highlightSectionKeys"] = ["section-99"]
    assert any("unknown section" in e for e in validate(data, bad_highlight)[0])
    extra_field = json.loads(json.dumps(good))
    extra_field["userId"] = "1"
    assert any(e.startswith("schema") for e in validate(data, extra_field)[0])

    longer = {**data, "segments": merged + [{"segmentId": "s0003", "startMs": 14000, "endMs": 100000, "text": "x"}]}
    assert any("cover only" in e for e in validate(longer, good)[0])
    one_section = {**good, "sections": [section]}  # The failure the schema exists to make impossible.
    assert any(e.startswith("schema") for e in validate(data, one_section)[0])
    empty = {**good, "contentStatus": "NOT_ANALYZABLE", "sections": []}
    assert validate(data, empty)[0] == []

    rules = {"mustInclude": [["오직없는말"], ["o"]], "forbidden": ["t"], "notRecommended": []}
    scored = score({**data, "chapters": [{"startMs": 0, "title": "c"}]}, good, rules)
    assert scored["valid"] and scored["coverage"] == "1/2" and scored["missing"] == ["오직없는말"], scored
    assert score(data, bad_ref, rules) == {"valid": False, "errors": 1, "warnings": 0}
    rows = [{"origin": "SOURCE", "kind": "examples", "said": "FULLY"}, {"origin": "SOURCE", "kind": "examples", "said": "PARTLY"},
            {"origin": "AI_SUGGESTION", "kind": "applicationTips", "said": "FULLY"}, {"origin": "SOURCE", "kind": "recommendedTools", "said": "PARTLY"}]
    assert tally(rows) == {"source": 2, "sourceFully": 1, "sourcePartly": 1, "sourceNo": 0, "ai": 1, "aiAlreadySaid": 1, "tools": 1, "toolsWrong": 1}
    gate_text = gate_message({"title": "t", "channel": "c", "category": "Education", "tags": ["a"], "description": "d </x> https://x.y"})
    assert "https" not in gate_text and "</x>" not in gate_text and "태그: a" in gate_text, gate_text
    print("selftest ok")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(required=True)
    p = commands.add_parser("normalize")
    p.add_argument("caption"), p.add_argument("info"), p.add_argument("out")
    p.set_defaults(func=cmd_normalize)
    p = commands.add_parser("tokens")
    p.add_argument("inputs", nargs="+")
    p.set_defaults(func=cmd_tokens)
    p = commands.add_parser("run")
    p.add_argument("input"), p.add_argument("out")
    p.add_argument("--model", default="gpt-6-luna")
    p.add_argument("--effort", default="low")
    p.add_argument("--max-tokens", type=int, default=16000)
    p.set_defaults(func=cmd_run)
    p = commands.add_parser("validate")
    p.add_argument("input"), p.add_argument("output")
    p.set_defaults(func=cmd_validate)
    p = commands.add_parser("round")
    p.add_argument("--tag", default="")
    p.add_argument("--only", nargs="*", help="Single-video experiment; tag it only-* so compare skips it.")
    p.add_argument("--model", default="gpt-6-luna")
    p.add_argument("--effort", default="low")
    p.add_argument("--max-tokens", type=int, default=16000)
    p.set_defaults(func=cmd_round)
    p = commands.add_parser("gate")
    p.add_argument("--model", default="gpt-6-luna")
    p.add_argument("--effort", default="low")
    p.add_argument("--repeat", type=int, default=3)
    p.add_argument("--bare", action="store_true")
    p.set_defaults(func=cmd_gate)
    p = commands.add_parser("judge")
    p.add_argument("run_dirs", nargs="+")
    p.add_argument("--model", default="gpt-6.1-terra")
    p.add_argument("--effort", default="medium")
    p.set_defaults(func=cmd_judge)
    p = commands.add_parser("compare")
    p.set_defaults(func=cmd_compare)
    p = commands.add_parser("show")
    p.add_argument("input"), p.add_argument("output")
    p.set_defaults(func=cmd_show)
    p = commands.add_parser("score")
    p.add_argument("input"), p.add_argument("outputs", nargs="+")
    p.set_defaults(func=cmd_score)
    p = commands.add_parser("evidence")
    p.add_argument("input"), p.add_argument("output")
    p.set_defaults(func=cmd_evidence)
    p = commands.add_parser("selftest")
    p.set_defaults(func=cmd_selftest)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
