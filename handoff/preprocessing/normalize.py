"""Analysis groups with an explicit reversible mapping to Spring CaptionDocument IDs."""
import copy
import re


def normalize(document, *, duration_ms, metadata=None):
    if not isinstance(document, dict):
        raise ValueError("CaptionDocument must be an object")
    if not isinstance(document.get("videoId"), str) or not document["videoId"].strip():
        raise ValueError("videoId must be nonempty")
    if document.get("language") != "ko" or type(duration_ms) is not int or not 0 < duration_ms <= 3600000:
        raise ValueError("language/duration does not match CaptionDocument contract")
    originals = document.get("segments")
    if not isinstance(originals, list) or not originals:
        raise ValueError("empty original captions")
    seen, previous, cleaned, excluded = set(), -1, [], []
    for raw in originals:
        if not isinstance(raw, dict):
            raise ValueError("caption segment must be an object")
        sid, start, end, text = (raw.get(k) for k in ("segmentId", "startMs", "endMs", "text"))
        if (not isinstance(sid, str) or not sid.strip() or sid in seen or type(start) is not int
                or type(end) is not int or not 0 <= start < end <= duration_ms or start < previous
                or not isinstance(text, str) or not text.strip()):
            raise ValueError("invalid original caption")
        seen.add(sid)
        previous = start
        text = re.sub(r"\s+", " ", text).strip()
        if re.fullmatch(r"\[[^\]]*\]", text):
            excluded.append(sid)
        else:
            cleaned.append({**raw, "text": text})
    groups, pending = [], []

    def flush():
        groups.append({"groupId": f"g{len(groups)+1:06d}",
                       "originalSegmentIds": [s["segmentId"] for s in pending],
                       "startMs": pending[0]["startMs"],
                       "endMs": max(s["endMs"] for s in pending),
                       "text": " ".join(s["text"] for s in pending)})
        pending.clear()

    for index, raw in enumerate(cleaned):
        pending.append(raw)
        # Only grouping decisions use clipped rolling-caption ends; originals stay unchanged.
        clipped_end = min(raw["endMs"], cleaned[index+1]["startMs"]) if index+1 < len(cleaned) else raw["endMs"]
        span = clipped_end - pending[0]["startMs"]
        if span >= 20000 or (span >= 10000 and re.search(r"[.?!]$", raw["text"])):
            flush()
    if pending:
        flush()
    if not groups:
        raise ValueError("EMPTY_ANALYSIS_INPUT: do not fabricate a successful section")
    metadata = {} if metadata is None else metadata
    if not isinstance(metadata, dict):
        raise ValueError("metadata must be an object")
    for key in ("title", "description"):
        if key in metadata and not isinstance(metadata[key], str):
            raise ValueError(f"{key} must be text")
    tags = metadata.get("tags", [])
    if not isinstance(tags, list) or any(not isinstance(tag, str) for tag in tags):
        raise ValueError("tags must be a list of text")
    message = {"videoId": document["videoId"], "language": "ko", "durationMs": duration_ms, "groups": [
        {"groupId": g["groupId"], "text": g["text"]} for g in groups]}
    if "title" in metadata:
        message["title"] = metadata["title"]
    if "description" in metadata:
        message["description"] = re.sub(r"https?://\S+", "", metadata["description"]).strip()[:1500]
    if "tags" in metadata:
        kept = []
        for tag in tags:
            if len(", ".join(kept + [tag])) > 300:
                break
            kept.append(tag)
        message["tags"] = kept
    return {"modelInput": message, "groups": groups, "excludedSegmentIds": excluded,
            "originalDocument": copy.deepcopy(document)}


def expand_output(output, prepared):
    """Replace group IDs with ORIGINAL boundary IDs, never reuse group IDs as originals."""
    result = copy.deepcopy(output)
    lookup = {g["groupId"]: g for g in prepared["groups"]}

    def group(group_id):
        if group_id not in lookup:
            raise ValueError("unknown group ID")
        return lookup[group_id]

    def refs(ids):
        expanded = []
        for group_id in ids:
            expanded.extend(group(group_id)["originalSegmentIds"])
        return list(dict.fromkeys(expanded))

    for point in result["summary"]["practicalPoints"] + result["summary"]["mentionedTools"]:
        point["sourceSegmentIds"] = refs(point["sourceSegmentIds"])
    for section in result["sections"]:
        section["startSegmentId"] = group(section["startSegmentId"])["originalSegmentIds"][0]
        section["endSegmentId"] = group(section["endSegmentId"])["originalSegmentIds"][-1]
        section["sourceSegmentIds"] = refs(section["sourceSegmentIds"])
        for key in ("applicationTips", "recommendedTools", "examples"):
            for point in section["detail"][key]:
                point["sourceSegmentIds"] = refs(point["sourceSegmentIds"])
    return result
