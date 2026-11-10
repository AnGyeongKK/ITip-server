"""Reference semantic checks. Backend retains ownership, DB state and app-event validation."""
import json
import math
import re
from pathlib import Path
from uuid import UUID

ROOT = Path(__file__).resolve().parents[1]


class ContractError(ValueError):
    def __init__(self, code, *, retryable=False):
        self.code, self.retryable = code, retryable
        super().__init__(code)


def load_json(text):
    def finite_float(value):
        result = float(value)
        if not math.isfinite(result):
            raise ValueError("nonfinite JSON number")
        return result
    def unique(pairs):
        obj = {}
        for k, v in pairs:
            if k in obj:
                raise ValueError("duplicate JSON key")
            obj[k] = v
        return obj
    return json.loads(text, object_pairs_hook=unique, parse_float=finite_float,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))


def schema(name):
    return load_json((ROOT / "schemas" / (name + ".json")).read_text())


def validate_schema(output, name):
    from jsonschema import Draft202012Validator
    if list(Draft202012Validator(schema(name)).iter_errors(output)):
        raise ContractError("INVALID_MODEL_SCHEMA")


def validate_chat_input(data):
    if (not isinstance(data, dict) or set(data) != {"question", "contexts", "history"} or not isinstance(data["question"], str)
            or not data["question"].strip() or len(data["question"].encode("utf-16-le"))//2>4000):
        raise ValueError("question/contexts/history required")
    if not isinstance(data["contexts"], list):
        raise ValueError("contexts must be a list")
    ids = set()
    for context in data["contexts"]:
        if not isinstance(context, dict) or set(context) != {"contextId", "sectionIds", "title", "text", "startMs", "endMs"}:
            raise ValueError("invalid context fields")
        cid = context["contextId"]
        if not isinstance(cid, str) or not re.fullmatch(r"C[1-9][0-9]*", cid) or cid in ids:
            raise ValueError("invalid/duplicate contextId")
        ids.add(cid)
        if (not isinstance(context["sectionIds"], list) or not context["sectionIds"]
                or any(not isinstance(sid, str) for sid in context["sectionIds"])
                or len(set(context["sectionIds"])) != len(context["sectionIds"])):
            raise ValueError("missing/duplicate sectionIds")
        for sid in context["sectionIds"]:
            UUID(sid)
        if any(not isinstance(context[k], str) or not context[k].strip() for k in ("title", "text")):
            raise ValueError("empty context text")
        if type(context["startMs"]) is not int or type(context["endMs"]) is not int or not 0 <= context["startMs"] < context["endMs"]:
            raise ValueError("invalid context time")
    history = data["history"]
    if not isinstance(history, list) or len(history) % 2:
        raise ValueError("history must contain complete USER/ASSISTANT pairs")
    for i, turn in enumerate(history):
        if (not isinstance(turn, dict) or set(turn) != {"role", "content"} or turn["role"] != ("USER" if i % 2 == 0 else "ASSISTANT")
                or not isinstance(turn["content"], str) or not turn["content"].strip()):
            raise ValueError("invalid history pair")


def validate_chat(output, transmitted_input):
    validate_chat_input(transmitted_input)
    try:
        validate_schema(output, "chat.spring-v1")
    except ValueError as exc:
        raise ContractError("CHAT_INVALID_AI_RESPONSE") from exc
    allowed = {c["contextId"]: set(c["sectionIds"]) for c in transmitted_input["contexts"]}
    if not output["answer"].strip() or len(output["answer"].encode("utf-16-le")) // 2 > 65536 or len(output["citations"]) > 20:
        raise ContractError("CHAT_INVALID_AI_RESPONSE")
    cited = set()
    for item in output["citations"]:
        pair = (item["contextId"], item["sectionId"])
        if item["sectionId"] not in allowed.get(item["contextId"], set()):
            raise ContractError("CHAT_INVALID_AI_RESPONSE")
        cited.add(pair)
    # Java deduplicates valid pairs; keep the same behavior rather than introducing a new failure.
    insufficient = output["evidenceStatus"] == "INSUFFICIENT_EVIDENCE"
    if insufficient != (not cited):
        raise ContractError("CHAT_INVALID_AI_RESPONSE")
    return output


def validate_analysis(output, document, *, expanded=False, profile="spring-v1", require_full_coverage=False):
    if profile not in ("spring-v1", "recommended-v1"):
        raise ValueError("unknown analysis profile")
    if not expanded:
        validate_schema(output, "analysis." + profile)
    else:
        # One model group may expand into >5 original IDs. Do not apply model-only maxItems again.
        import copy
        from jsonschema import Draft202012Validator
        expanded_schema = copy.deepcopy(schema("analysis." + profile))
        def remove_ref_cap(node):
            if isinstance(node, dict):
                for k,v in node.items():
                    if k == "sourceSegmentIds":
                        v.pop("maxItems", None)
                    remove_ref_cap(v)
            elif isinstance(node, list):
                for value in node: remove_ref_cap(value)
        remove_ref_cap(expanded_schema)
        if list(Draft202012Validator(expanded_schema).iter_errors(output)):
            raise ValueError("invalid expanded output")
    def nonempty_strings(value):
        if isinstance(value,str) and not value.strip():raise ValueError("empty analysis text")
        if isinstance(value,dict):
            for child in value.values():nonempty_strings(child)
        elif isinstance(value,list):
            for child in value:nonempty_strings(child)
    nonempty_strings(output)
    if not output["mindmap"]["sectionKeys"]:raise ValueError("empty mindmap")
    segments = document["segments"]
    order = {s["segmentId"]: i for i,s in enumerate(segments)}
    if len(order) != len(segments): raise ValueError("duplicate original ID")
    keys = [s["sectionKey"] for s in output["sections"]]
    if len(set(keys)) != len(keys): raise ValueError("duplicate sectionKey")
    for refs in (output["summary"]["highlightSectionKeys"], output["mindmap"]["sectionKeys"]):
        if len(refs)!=len(set(refs)) or any(k not in keys for k in refs): raise ValueError("unknown/duplicate sectionKey")
    def check_refs(point, allowed):
        refs = point["sourceSegmentIds"]
        if point.get("origin", "SOURCE") == "SOURCE" and not refs: raise ValueError("SOURCE needs evidence")
        if any(r not in allowed for r in refs): raise ValueError("invalid/out-of-section evidence")
    for p in output["summary"]["practicalPoints"] + output["summary"]["mentionedTools"]:
        check_refs(p, order)
    previous = -1
    mapped = []
    for section in output["sections"]:
        if len(section["title"].encode("utf-16-le"))//2>500:raise ValueError("section title too long")
        start,end = order.get(section["startSegmentId"],-1),order.get(section["endSegmentId"],-1)
        if not previous < start <= end: raise ValueError("invalid/reordered/overlapping section boundary")
        if require_full_coverage and start != previous + 1:
            raise ValueError("analysis groups have a coverage gap")
        previous=end
        allowed=set(s["segmentId"] for s in segments[start:end+1])
        check_refs(section, allowed)
        for key in ("applicationTips","recommendedTools","examples"):
            for point in section["detail"][key]: check_refs(point, allowed)
        mapped.append({"sectionKey":section["sectionKey"],"startMs":segments[start]["startMs"],
                       "endMs":max(s["endMs"] for s in segments[start:end+1])})
    if require_full_coverage and previous != len(segments)-1:
        raise ValueError("analysis groups do not cover final captions")
    return mapped


def provider_status(reason, *, refusal=False, kind="chat"):
    if kind not in ("analysis", "chat"):
        raise ValueError("unknown invocation kind")
    reason = reason.lower() if isinstance(reason, str) else None
    if refusal or reason == "content_filter":
        raise ContractError("CHAT_AI_REFUSED" if kind == "chat" else "AI_REQUEST_REJECTED")
    if reason == "length":
        raise ContractError("CHAT_ANSWER_TOO_LONG" if kind == "chat" else "AI_INVALID_RESPONSE")
    if reason != "stop":
        raise ContractError("CHAT_STREAM_FAILED" if kind == "chat" else "AI_UNAVAILABLE", retryable=True)
