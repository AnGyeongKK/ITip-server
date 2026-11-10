#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["openai>=1.60", "jsonschema>=4", "tiktoken>=0.8"]
# ///
"""Answer-generation experiment. No retrieval, database, HTTP server, or SSE transport."""
import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path

from ai_runtime import AIError, check_token_budget, load_json
from chat_stream import ContentDecoder

HERE = Path(__file__).resolve().parent
PROMPT = (HERE / "chat_prompt.md").read_text()
SCHEMA = json.loads((HERE / "chat.schema.json").read_text())
VERSION = hashlib.sha256((PROMPT + json.dumps(SCHEMA)).encode()).hexdigest()[:8]
SOURCE_FIELDS = ("sourceId", "recordId", "analysisId", "sectionId", "title", "videoId",
                 "startMs", "endMs", "availability", "analyzedAt")


def validate_input(data):
    """Local experiment envelope; ownership checks are performed by the backend."""
    if not isinstance(data, dict):
        raise ValueError("input must be an object")
    if not isinstance(data.get("question"), str) or not data["question"].strip():
        raise ValueError("question must be nonempty")
    if not isinstance(data.get("evidence"), list):
        raise ValueError("evidence must be a list")
    if not isinstance(data.get("history", []), list):
        raise ValueError("history must be a list")
    if len(data["question"]) > 8000 or len(data["evidence"]) > 100 or len(data.get("history", [])) > 40:
        raise AIError("CHAT_INPUT_LIMIT_EXCEEDED")
    for turn in data.get("history", []):
        if (not isinstance(turn, dict) or turn.get("role") not in ("user", "assistant")
                or not isinstance(turn.get("content"), str)):
            raise ValueError("history must contain user/assistant text only")
        if len(turn["content"]) > 32000:
            raise AIError("CHAT_INPUT_LIMIT_EXCEEDED")
    ids = set()
    for source in data["evidence"]:
        if not isinstance(source, dict) or any(k not in source for k in SOURCE_FIELDS):
            raise ValueError("evidence requires CHAT-04 source metadata")
        sid = source["sourceId"]
        if not isinstance(sid, str) or not sid.strip() or sid in ids:
            raise ValueError("sourceIds must be nonempty and unique")
        ids.add(sid)
        for key in ("recordId", "analysisId", "sectionId", "title", "videoId", "analyzedAt"):
            if not isinstance(source[key], str) or not source[key].strip():
                raise ValueError(f"{sid}: {key} must be nonempty text")
        if source["availability"] != "AVAILABLE":
            raise ValueError(f"{sid}: current evidence must be AVAILABLE")
        if (type(source["startMs"]) is not int or type(source["endMs"]) is not int
                or not 0 <= source["startMs"] < source["endMs"]):
            raise ValueError(f"{sid}: invalid source time range")
        if not isinstance(source.get("text"), str) or not source["text"].strip():
            raise ValueError(f"{sid}: text must be nonempty")
        if len(source["text"]) > 100000:
            raise AIError("CHAT_INPUT_LIMIT_EXCEEDED")
        if source.get("origin") not in ("SOURCE", "AI_SUGGESTION"):
            raise ValueError(f"{sid}: origin must be explicit")


def messages(data):
    validate_input(data)
    # Everything except the fixed prompt remains untrusted data. Server metadata stays local.
    payload = {
        "question": data["question"], "history": data.get("history", []),
        "evidence": [{k: s[k] for k in ("sourceId", "title", "text", "origin")}
                     for s in data["evidence"]],
    }
    return [{"role": "system", "content": PROMPT},
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)}]


def validate_output(data, output):
    from jsonschema import Draft202012Validator

    validate_input(data)
    errors = [f"schema {list(e.path)}: {e.message}"
              for e in Draft202012Validator(SCHEMA).iter_errors(output)]
    if errors:
        return errors
    if not output["content"].strip():
        errors.append("content must be nonempty")
    if len(output["content"]) > 16000:
        errors.append("content exceeds local output limit")
    ids = output["sourceIds"]
    if len(ids) != len(set(ids)):
        errors.append("duplicate sourceId")
    allowed = {s["sourceId"] for s in data["evidence"] if s["origin"] == "SOURCE"}
    errors.extend(f"unavailable or non-SOURCE sourceId: {sid}" for sid in ids if sid not in allowed)
    if output["finishReason"] == "STOP" and not ids:
        errors.append("STOP requires a source")
    if output["finishReason"] == "INSUFFICIENT_EVIDENCE" and ids:
        errors.append("INSUFFICIENT_EVIDENCE requires empty sources")
    return errors


def materialize(data, output):
    """CHAT-03/04 answer fields; server-owned metadata is copied, never generated."""
    errors = validate_output(data, output)
    if errors:
        raise ValueError("; ".join(errors))
    lookup = {s["sourceId"]: s for s in data["evidence"]}
    sources = [{k: lookup[sid][k] for k in SOURCE_FIELDS} for sid in output["sourceIds"]]
    return {"content": output["content"], "sources": sources,
            "finishReason": output["finishReason"],
            "usedRecordCount": len({s["recordId"] for s in sources})}


def generate(data, model, effort, max_tokens):
    from openai import OpenAI

    prepared = messages(data)
    check_token_budget(prepared, SCHEMA, 24000)
    began = time.monotonic()
    # No hidden SDK retries; one case means one model request. Backend owns retry policy.
    with OpenAI(max_retries=0, timeout=150,
                default_headers={"Accept-Encoding": "gzip"}) as client:
        response = client.chat.completions.create(
            model=model, reasoning_effort=effort, max_completion_tokens=max_tokens,
            messages=prepared,
            response_format={"type": "json_schema", "json_schema": {
                "name": "yousum_chat", "strict": True, "schema": SCHEMA}},
        )
    choice = response.choices[0]
    if choice.message.refusal or choice.finish_reason != "stop":
        raise ValueError(f"unusable model response: {choice.finish_reason}; "
                         f"refusal={bool(choice.message.refusal)}")
    output = load_json(choice.message.content)
    answer = materialize(data, output)
    return {"modelOutput": output, "answer": answer, "meta": {
        "model": response.model, "requestedModel": model, "effort": effort,
        "promptVersion": VERSION, "seconds": round(time.monotonic() - began, 2),
        "usage": response.usage.model_dump() if response.usage else None,
    }}


def generate_stream(data, model, effort, max_tokens):
    """Yield content deltas, then a validated result. Closing the generator closes the HTTP stream.

    The caller must close this generator on cancellation and persist before publishing SSE done.
    """
    from openai import OpenAI

    prepared = messages(data)
    estimate = check_token_budget(prepared, SCHEMA, 24000)
    began = time.monotonic()
    decoder = ContentDecoder()
    finish_reason, usage, actual_model, first_delta = None, None, model, None
    with OpenAI(max_retries=0, timeout=150, default_headers={"Accept-Encoding": "gzip"}) as client:
        with client.chat.completions.create(
            model=model, reasoning_effort=effort, max_completion_tokens=max_tokens,
            messages=prepared, stream=True, stream_options={"include_usage": True},
            response_format={"type": "json_schema", "json_schema": {
                "name": "yousum_chat", "strict": True, "schema": SCHEMA}},
        ) as stream:
            for chunk in stream:
                if time.monotonic() - began > 150:
                    raise AIError("CHAT_DEADLINE_EXCEEDED", retryable=True)
                actual_model = chunk.model
                if chunk.usage is not None:
                    usage = chunk.usage.model_dump()
                for choice in chunk.choices:
                    if choice.index != 0:
                        raise AIError("UNEXPECTED_STREAM_CHOICE")
                    if choice.delta.refusal:
                        raise AIError("MODEL_REFUSAL")
                    if choice.delta.content:
                        if finish_reason is not None:
                            raise AIError("CONTENT_AFTER_STREAM_FINISH")
                        delta = decoder.feed(choice.delta.content)
                        if delta:
                            if first_delta is None:
                                first_delta = round(time.monotonic() - began, 3)
                            yield {"type": "delta", "text": delta}
                    if choice.finish_reason is not None:
                        finish_reason = choice.finish_reason
    if finish_reason != "stop":
        raise AIError("MODEL_OUTPUT_INCOMPLETE", retryable=True)
    output = decoder.finish()
    answer = materialize(data, output)
    yield {"type": "result", "result": {"modelOutput": output, "answer": answer, "meta": {
        "model": actual_model, "requestedModel": model, "effort": effort, "promptVersion": VERSION,
        "seconds": round(time.monotonic() - began, 3), "firstDeltaSeconds": first_delta,
        "estimatedInputTokens": estimate, "usage": usage,
    }}}


def collect_stream(data, model, effort, max_tokens):
    pieces, result = [], None
    for event in generate_stream(data, model, effort, max_tokens):
        if event["type"] == "delta":
            pieces.append(event["text"])
        else:
            result = event["result"]
    if result is None or "".join(pieces) != result["answer"]["content"]:
        raise AIError("STREAM_CONTENT_MISMATCH")
    result["streamCheck"] = {"deltaCount": len(pieces), "matchesStoredContent": True}
    return result


def check_expectations(case, result):
    """Structural expectations only; grounding and wording still need human review."""
    answer, expected = result["answer"], case["expected"]
    errors = []
    if answer["finishReason"] != expected["finishReason"]:
        errors.append("unexpected finishReason")
    ids = {s["sourceId"] for s in answer["sources"]}
    if ids != set(expected["sourceIds"]):
        errors.append(f"unexpected sources: {sorted(ids)}")
    for text in expected.get("mustContain", []):
        if text not in answer["content"]:
            errors.append(f"missing text: {text}")
    for text in expected.get("mustNotContain", []):
        if text in answer["content"]:
            errors.append(f"forbidden text: {text}")
    return errors


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("request", "run", "stream", "round", "validate"))
    parser.add_argument("input", help="input JSON, or evaluation cases JSON for round")
    parser.add_argument("output", nargs="?", help="output file/directory; validate: model output JSON")
    parser.add_argument("--model", default=os.getenv("CHAT_MODEL", "gpt-6-luna"))
    parser.add_argument("--effort", default="low")
    parser.add_argument("--max-tokens", type=int, default=4096)
    parser.add_argument("--stream", action="store_true", help="Use live streaming for run/round")
    args = parser.parse_args()
    if args.max_tokens <= 0 or args.max_tokens > 16000:
        parser.error("max-tokens must be between 1 and 16000")
    data = load_json(Path(args.input).read_text())
    call = collect_stream if args.stream else generate
    if args.command == "request":
        print(json.dumps({"messages": messages(data), "schema": SCHEMA}, ensure_ascii=False, indent=2))
    elif args.command == "validate":
        if not args.output:
            parser.error("validate requires a model output JSON")
        print(json.dumps(materialize(data, load_json(Path(args.output).read_text())), ensure_ascii=False, indent=2))
    elif args.command == "stream":
        if not args.output:
            parser.error("stream requires a result file")
        for event in generate_stream(data, args.model, args.effort, args.max_tokens):
            if event["type"] == "result":
                write_json(args.output, event["result"])
            print(json.dumps(event, ensure_ascii=False), flush=True)
    elif args.command == "run":
        if not args.output:
            parser.error("run requires an output path")
        result = call(data, args.model, args.effort, args.max_tokens)
        write_json(args.output, result)
        print(json.dumps(result["meta"], ensure_ascii=False))
    else:
        if not args.output:
            parser.error("round requires an output directory")
        folder = Path(args.output)
        if folder.exists():
            parser.error("round output directory already exists; use a new directory")
        cases = data["cases"]
        case_ids = [c["id"] for c in cases]
        if len(set(case_ids)) != len(case_ids) or any(
                not isinstance(i, str) or not i or Path(i).name != i or i in (".", "..")
                for i in case_ids):
            parser.error("case IDs must be unique filenames")
        for case in cases:
            validate_input(case["input"])
        folder.mkdir(parents=True)
        write_json(folder / "cases.json", data)
        (folder / "prompt.md").write_text(PROMPT)
        write_json(folder / "schema.json", SCHEMA)
        summary = []
        for case in cases:
            try:
                result = call(case["input"], args.model, args.effort, args.max_tokens)
                errors = check_expectations(case, result)
                result["expectationErrors"] = errors
                write_json(folder / f"{case['id']}.json", result)
                row = {"id": case["id"], "passed": not errors, "errors": errors,
                       "seconds": result["meta"]["seconds"]}
            except Exception as exc:
                # Do not print provider response bodies or credentials into experiment logs.
                row = {"id": case["id"], "passed": False, "errorType": type(exc).__name__}
            summary.append(row)
            write_json(folder / "summary.json", summary)
            print(json.dumps(row, ensure_ascii=False), flush=True)
        if any(not row["passed"] for row in summary):
            sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except AIError as exc:
        print(json.dumps({"type": "error", "code": exc.code, "retryable": exc.retryable}), file=sys.stderr)
        sys.exit(1)
    except Exception as exc:
        print(json.dumps({"type": "error", "code": type(exc).__name__, "retryable": False}), file=sys.stderr)
        sys.exit(1)
