"""Shared local runtime rules for the AI handoff; no database or retrieval."""
import json
import re


class AIError(ValueError):
    def __init__(self, code, *, retryable=False):
        self.code, self.retryable = code, retryable
        super().__init__(code)


def load_json(text):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise AIError("DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    return json.loads(text, object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(AIError("INVALID_JSON_NUMBER")))


def gate_action(verdict, confirmed=False):
    if type(confirmed) is not bool:
        raise AIError("INVALID_CONFIRMATION")
    if verdict == "NOT_IT":
        return "REJECT"
    if verdict == "UNSURE":
        return "ANALYZE" if confirmed else "CONFIRM_REQUIRED"
    if verdict == "IT":
        return "ANALYZE"
    raise AIError("INVALID_GATE_VERDICT")


def check_language(language):
    # Generic ko needs native-client provenance; translated tracks must never be relabeled ko.
    if language not in ("ko", "ko-orig", "ko-ko"):
        raise AIError("UNSUPPORTED_CAPTION_LANGUAGE")


def validate_analysis_input(data):
    if not isinstance(data, dict):
        raise AIError("INVALID_ANALYSIS_INPUT")
    for name in ("videoId", "title"):
        if not isinstance(data.get(name), str) or not data[name].strip():
            raise AIError("INVALID_VIDEO_METADATA")
    duration = data.get("durationMs")
    if type(duration) is not int or not 0 < duration <= 3_600_000:
        raise AIError("UNSUPPORTED_VIDEO_DURATION")
    check_language(data.get("language"))
    if data.get("isTranslated", False) is not False:
        raise AIError("UNSUPPORTED_CAPTION_LANGUAGE")
    segments = data.get("segments")
    if not isinstance(segments, list) or not segments or len(segments) > 50_000:
        raise AIError("INVALID_CAPTION_SEGMENTS")
    ids, previous_end = set(), 0
    for item in segments:
        if not isinstance(item, dict):
            raise AIError("INVALID_CAPTION_SEGMENT")
        sid, start, end, text = (item.get(k) for k in ("segmentId", "startMs", "endMs", "text"))
        if not isinstance(sid, str) or not re.fullmatch(r"s\d+", sid) or sid in ids:
            raise AIError("INVALID_SEGMENT_ID")
        if type(start) is not int or type(end) is not int or not previous_end <= start < end <= duration:
            raise AIError("INVALID_CAPTION_TIME")
        if not isinstance(text, str) or not text.strip():
            raise AIError("EMPTY_CAPTION_TEXT")
        ids.add(sid)
        previous_end = end
    if not isinstance(data.get("description", ""), str):
        raise AIError("INVALID_VIDEO_METADATA")
    tags = data.get("tags", [])
    if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
        raise AIError("INVALID_VIDEO_METADATA")


def prepare_captions(upload, metadata):
    """Android's normalized upload -> server IDs and 10–20 second groups."""
    from yousum_ai import merge_segments

    if not isinstance(upload, dict) or not isinstance(metadata, dict):
        raise AIError("INVALID_ANALYSIS_INPUT")
    if upload.get("videoId") != metadata.get("videoId"):
        raise AIError("VIDEO_ID_MISMATCH")
    duration = metadata.get("durationMs")
    if type(duration) is not int or not 0 < duration <= 3_600_000:
        raise AIError("UNSUPPORTED_VIDEO_DURATION")
    raw = upload.get("segments")
    if not isinstance(raw, list) or len(raw) > 50_000:
        raise AIError("INVALID_CAPTION_SEGMENTS")
    # Check all uploaded ranges before merging. Clip only rolling overlaps; never reorder.
    parts, previous_start, clipped_ends = [], -1, 0
    for item in raw:
        if not isinstance(item, dict):
            raise AIError("INVALID_CAPTION_SEGMENT")
        start, end, text = (item.get(k) for k in ("startMs", "endMs", "text"))
        if (type(start) is not int or type(end) is not int or start < previous_start
                or not 0 <= start < end or start >= duration):
            raise AIError("INVALID_CAPTION_TIME")
        if end > duration:
            end = duration
            clipped_ends += 1
        previous_start = start
        if not isinstance(text, str):
            raise AIError("INVALID_CAPTION_TEXT")
        text = re.sub(r"\s+", " ", text).strip()
        if text and not re.fullmatch(r"\[[^\]]*\]", text):
            parts.append({"startMs": start, "endMs": end, "text": text})
    for current, following in zip(parts, parts[1:]):
        current["endMs"] = min(current["endMs"], following["startMs"])
    parts = [part for part in parts if part["endMs"] > part["startMs"]]
    data = {**metadata, "language": upload.get("language"),
            "isTranslated": upload.get("isTranslated", False), "segments": merge_segments(parts),
            "normalization": {"clippedVideoEndCount": clipped_ends}}
    validate_analysis_input(data)
    return data


def validate_analysis_output(data, output):
    """Strict handoff validation, including complete coverage and all local references."""
    from yousum_ai import validate

    validate_analysis_input(data)
    errors, warnings, mapped = validate(data, output)
    if errors:
        return errors, warnings, mapped
    if output["contentStatus"] == "NOT_ANALYZABLE":
        summary, mindmap = output["summary"], output["mindmap"]
        if any(summary.values()) or any(mindmap.values()):
            errors.append("NOT_ANALYZABLE requires empty summary and mindmap")
        return errors, warnings, mapped
    order = {s["segmentId"]: i for i, s in enumerate(data["segments"])}
    sections = output["sections"]
    expected_start = 0
    for section in sections:
        start, end = order[section["startSegmentId"]], order[section["endSegmentId"]]
        if start != expected_start:
            errors.append(f"{section['sectionKey']}: incomplete caption coverage")
        expected_start = end + 1
        refs = list(section["sourceSegmentIds"])
        if any(not start <= order[sid] <= end for sid in refs):
            errors.append(f"{section['sectionKey']}: representative evidence outside section")
        detail_refs = []
        for kind in ("applicationTips", "examples", "recommendedTools"):
            for item in section["detail"][kind]:
                detail_refs.extend(item["sourceSegmentIds"])
        if any(not start <= order[sid] <= end for sid in detail_refs):
            warnings.append(f"{section['sectionKey']}: detail cites context from another section")
        if not section["title"].strip() or not section["summary"].strip():
            errors.append(f"{section['sectionKey']}: empty section text")
    if expected_start != len(order):
        errors.append("sections do not cover final captions")
    keys = [s["sectionKey"] for s in sections]
    if output["mindmap"]["sectionKeys"] != keys:
        errors.append("mindmap must reference every section once in order")
    highlights = output["summary"]["highlightSectionKeys"]
    if not highlights or len(highlights) != len(set(highlights)):
        errors.append("highlights must be nonempty and unique")
    return errors, warnings, mapped


def check_token_budget(messages, schema, max_input_tokens):
    """Conservative local estimate, not a replacement for provider usage accounting."""
    import tiktoken

    if type(max_input_tokens) is not int or max_input_tokens <= 0:
        raise AIError("INVALID_TOKEN_BUDGET")
    encoding = tiktoken.get_encoding("o200k_base")
    size = len(encoding.encode(json.dumps(messages, ensure_ascii=False)))
    size += len(encoding.encode(json.dumps(schema))) + 256
    if size > max_input_tokens:
        raise AIError("INPUT_TOKEN_BUDGET_EXCEEDED")
    return size
