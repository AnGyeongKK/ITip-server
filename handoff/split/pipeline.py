"""Experimental split analysis; final Spring schema and original semantic checks stay authoritative."""
import copy
import json
from pathlib import Path
import re

from jsonschema import Draft202012Validator

HERE = Path(__file__).parent
MODEL = "gpt-6-luna"
EFFORT = "low"
TOTAL_COMPLETION_BUDGET = 16000
CAPS = {"plan":1024,"detail":4096,"summary":3072}
INPUT_BUDGET = 30000
CONCURRENCY = 3


def nonblank():
    return {"type":"string","pattern":r"\S"}


def obj(properties):
    return {"type":"object","additionalProperties":False,"required":list(properties),"properties":properties}


def identifiers(message):
    ids = [g["groupId"] for g in message["groups"]]
    if not ids or any(not isinstance(i,str) or not i.strip() for i in ids) or len(ids) != len(set(ids)):
        raise ValueError("unique nonempty group IDs required")
    return ids


def constrain(base, allowed_ids, section_keys=None):
    result = copy.deepcopy(base)
    def walk(node):
        if isinstance(node,dict):
            if node.get("type") == "string" and "enum" not in node:
                node["pattern"] = r"\S"
            for name, value in node.get("properties",{}).items():
                if name == "sourceSegmentIds":
                    value["items"] = {"$ref":"#/$defs/sourceId"}
                if name == "highlightSectionKeys":
                    value["items"] = {"type":"string","enum":section_keys}
            for value in node.values():
                walk(value)
        elif isinstance(node,list):
            for value in node:
                walk(value)
    walk(result)
    result["$defs"] = {"sourceId":{"type":"string","enum":allowed_ids}}
    return result


def plan_schema(message):
    ids = identifiers(message)
    return obj({"earlierSections":{"type":"array","minItems":0,"maxItems":min(14,len(ids)-1),
        "items":obj({"title":nonblank(),"endSegmentId":{"type":"string","enum":ids[:-1] or ids}})},
        "lastSectionTitle":nonblank()})


def detail_schema(base, ids):
    props = base["properties"]["sections"]["items"]["properties"]
    fields = {k:copy.deepcopy(props[k]) for k in ("sourceSegmentIds","summary","subtopics","keywords","detail")}
    fields["mentionedTools"] = copy.deepcopy(base["properties"]["summary"]["properties"]["mentionedTools"])
    return constrain(obj(fields),ids)


def summary_schema(base, ids, keys):
    return constrain(obj({"summary":copy.deepcopy(base["properties"]["summary"]),"rootTitle":nonblank()}),ids,keys)


def validate_stage(output, schema):
    Draft202012Validator(schema).validate(output)
    def visit(value):
        if isinstance(value,str) and not value.strip():
            raise ValueError("empty stage text")
        if isinstance(value,dict):
            for child in value.values():visit(child)
        elif isinstance(value,list):
            for child in value:visit(child)
    visit(output)


def freeze_sections(message, output):
    validate_stage(output,plan_schema(message))
    ids = identifiers(message)
    order = {gid:i for i,gid in enumerate(ids)}
    previous, sections = -1, []
    for item in output["earlierSections"]:
        end = order[item["endSegmentId"]]
        if not previous < end < len(ids)-1:
            raise ValueError("invalid/reordered/duplicate plan boundary")
        sections.append({"sectionKey":f"section-{len(sections)+1:02d}","title":item["title"],
            "startSegmentId":ids[previous+1],"endSegmentId":ids[end]})
        previous = end
    sections.append({"sectionKey":f"section-{len(sections)+1:02d}","title":output["lastSectionTitle"],
        "startSegmentId":ids[previous+1],"endSegmentId":ids[-1]})
    return sections


def section_groups(message, section):
    ids = identifiers(message)
    start, end = ids.index(section["startSegmentId"]), ids.index(section["endSegmentId"])
    if start > end:
        raise ValueError("invalid fixed section")
    return copy.deepcopy(message["groups"][start:end+1])


def detail_message(message, section):
    return {**{k:copy.deepcopy(v) for k,v in message.items() if k != "groups"},
            "section":copy.deepcopy(section),"groups":section_groups(message,section)}


def finish_section(section, output):
    return {**copy.deepcopy(section),**{k:copy.deepcopy(v) for k,v in output.items() if k != "mentionedTools"}}


def summary_message(message, sections, detail_outputs):
    refs = set()
    def visit(value):
        if isinstance(value,dict):
            refs.update(value.get("sourceSegmentIds",[]))
            for child in value.values():visit(child)
        elif isinstance(value,list):
            for child in value:visit(child)
    visit(sections)
    visit(detail_outputs)
    # Keep all cited originals plus numeric originals to avoid summarizing only prior summaries.
    evidence = [copy.deepcopy(g) for g in message["groups"] if g["groupId"] in refs or re.search(r"\d",g["text"])]
    if not evidence:
        raise ValueError("no original summary evidence")
    return {**{k:copy.deepcopy(v) for k,v in message.items() if k != "groups"},
            "sections":copy.deepcopy(sections),"sectionMentionedTools":[copy.deepcopy(d["mentionedTools"]) for d in detail_outputs],
            "evidence":evidence}


def request(stage, message, schema, cap, count_input):
    system = (HERE/(stage+".system.md")).read_text()
    estimated = count_input(system,message,schema)
    if estimated > INPUT_BUDGET:
        raise ValueError("SPLIT_INPUT_TOO_LARGE")
    if not 1 <= cap <= CAPS[stage]:
        raise ValueError("invalid stage completion cap")
    Draft202012Validator.check_schema(schema)
    body = {"model":MODEL,"reasoning_effort":EFFORT,"max_completion_tokens":cap,
        "messages":[{"role":"system","content":system},{"role":"user","content":json.dumps(message,ensure_ascii=False)}],
        "response_format":{"type":"json_schema","json_schema":{"name":"analysis_split_"+stage+"_v1","strict":True,"schema":schema}}}
    return body, {"estimatedInputTokens":estimated,"inputBudget":INPUT_BUDGET,"estimator":"o200k_base proxy"}


def detail_caps(used_tokens, batch_size):
    available = TOTAL_COMPLETION_BUDGET-used_tokens-CAPS["summary"]
    cap = min(CAPS["detail"],available//batch_size)
    if cap < 1:
        raise ValueError("SPLIT_COMPLETION_BUDGET_EXHAUSTED")
    return [cap]*batch_size


def assemble(sections, summary_output):
    return {"schemaVersion":"1.0","sections":copy.deepcopy(sections),"summary":copy.deepcopy(summary_output["summary"]),
        "mindmap":{"rootTitle":summary_output["rootTitle"],"sectionKeys":[s["sectionKey"] for s in sections]}}
