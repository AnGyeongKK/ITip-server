"""Python experiment CLI for Spring handoff. No HTTP server or app SSE generation."""
import argparse
import copy
import hashlib
import importlib.metadata
import json
import sys
import time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from preprocessing.normalize import normalize, expand_output
from validation.contracts import (ContractError, load_json, schema, validate_chat, validate_chat_input,
                                  validate_analysis, validate_schema, provider_status)
from invocation.answer_stream import AnswerDecoder


def dump(value): return json.dumps(value,ensure_ascii=False,separators=(",",":"))


def count_input(system,data,output_schema):
    import tiktoken
    enc=tiktoken.get_encoding("o200k_base")
    return sum(len(enc.encode(v,disallowed_special=())) for v in (system,dump(data),dump(output_schema)))+128


def prepare_chat(data, system, output_schema, budget=8000):
    if type(budget) is not int or budget <= 0:
        raise ValueError("input budget must be a positive integer")
    validate_chat_input(data)
    kept=copy.deepcopy(data)
    utf16=lambda s:len(s.encode("utf-16-le"))//2
    # This reference starts from backend-authorized complete history pairs and ranked contexts.
    while len(kept["history"])>10 or sum(utf16(h["content"]) for h in kept["history"])>32000:
        del kept["history"][:2]
    while len(kept["contexts"])>20 or sum(utf16(c["text"]) for c in kept["contexts"])>24000:
        kept["contexts"].pop()
    while True:
        transmitted=copy.deepcopy(kept)
        for i,c in enumerate(transmitted["contexts"],1): c["contextId"]=f"C{i}"
        estimated=count_input(system,transmitted,output_schema)
        if estimated<=budget:
            return transmitted,{"estimatedInputTokens":estimated,"inputBudget":budget,
                "removedHistoryMessages":len(data["history"])-len(kept["history"]),
                "contextMapping":[{"contextId":f"C{i}","inputContextId":c["contextId"]} for i,c in enumerate(kept["contexts"],1)]}
        if kept["history"]: del kept["history"][:2]
        elif kept["contexts"]: kept["contexts"].pop()
        else: raise ContractError("CHAT_PROMPT_TOO_LARGE")


def request(kind,data,profile="spring-v1", *, stream=False):
    if kind not in ("analysis", "chat") or profile not in ("spring-v1", "recommended-v1"):
        raise ValueError("unknown invocation kind/profile")
    if kind=="chat" and profile!="spring-v1":raise ValueError("chat supports spring-v1 only")
    if stream and kind != "chat":raise ValueError("stream example is chat-only")
    name=f"{kind}.{profile}"
    system=(ROOT/"prompts"/(kind+".spring-v1.system.md")).read_text()
    output_schema=schema(name)
    extra={}
    if kind=="chat": data,extra=prepare_chat(data,system,output_schema)
    else:
        if profile == "recommended-v1" and len(data.get("groups", [])) < 2:
            raise ValueError("minimum-two-section profile requires at least two analysis groups")
        if count_input(system,data,output_schema)>30000: raise ValueError("ANALYSIS_INPUT_TOO_LARGE")
    body={"model":"gpt-6-luna","reasoning_effort":"low",
          "max_completion_tokens":2048 if kind=="chat" else 16000,
          "messages":[{"role":"system","content":system},{"role":"user","content":dump(data)}],
          "response_format":{"type":"json_schema","json_schema":{"name":name.replace(".","_"),"strict":True,"schema":output_schema}}}
    if stream:
        body.update(stream=True, stream_options={"include_usage": True})
    return body,data,extra


def classify_error(exc, kind):
    """Return codes only. Provider messages can contain prompts, URLs or credentials."""
    from openai import APIStatusError, APIConnectionError
    if isinstance(exc, ContractError):
        return exc
    if isinstance(exc, APIStatusError):
        transient = exc.status_code in (408, 429) or exc.status_code >= 500
        code = "CHAT_STREAM_FAILED" if kind == "chat" else (
            "AI_UNAVAILABLE" if transient else "AI_REQUEST_REJECTED")
        return ContractError(code, retryable=transient)
    if isinstance(exc, (APIConnectionError, TimeoutError)):
        return ContractError("CHAT_STREAM_FAILED" if kind == "chat" else "AI_UNAVAILABLE", retryable=True)
    if isinstance(exc, (ValueError, TypeError, KeyError)):
        return ContractError("CHAT_INVALID_AI_RESPONSE" if kind == "chat" else "AI_INVALID_RESPONSE")
    return ContractError("CHAT_STREAM_FAILED" if kind == "chat" else "AI_REQUEST_FAILED")


def consume_stream(chunks, record, *, began):
    decoder = AnswerDecoder()
    for event in chunks:
        record["providerChunks"].append(event.model_dump(mode="json"))
        if event.usage is not None:
            record["metadata"]["usage"] = event.usage.model_dump()
        if len(event.choices) > 1:
            raise ContractError("CHAT_INVALID_AI_RESPONSE")
        for choice in event.choices:
            if record["providerFinishReason"] is not None:
                raise ContractError("CHAT_INVALID_AI_RESPONSE")
            if choice.index != 0 or choice.delta.tool_calls:
                raise ContractError("CHAT_INVALID_AI_RESPONSE")
            if choice.delta.refusal:
                record["refusalObserved"] = True
                provider_status(None, refusal=True)
            if choice.delta.content:
                text = decoder.feed(choice.delta.content)
                if text:
                    if record["metadata"]["firstDeltaSeconds"] is None:
                        record["metadata"]["firstDeltaSeconds"] = round(time.monotonic()-began, 3)
                    record["answerDeltas"].append(text)
            if choice.finish_reason:
                record["providerFinishReason"] = choice.finish_reason
    provider_status(record["providerFinishReason"])
    record["response"] = decoder.finish()


def invoke(kind, data, *, stream=False, profile="spring-v1", record=None, prepared_request=None):
    from openai import OpenAI, OpenAIError
    if stream and kind != "chat":
        raise ValueError("stream example is chat-only")
    body, transmitted, budget = prepared_request or request(kind, data, profile, stream=stream)
    if body.get("stream", False) != stream:
        raise ValueError("prepared request stream option does not match invocation")
    record = {} if record is None else record
    record.update({"request": body, "transmittedInput": transmitted, "providerFinishReason": None,
        "providerChunks": [], "answerDeltas": [], "metadata": {
            "api": "Chat Completions", "sdk": "openai-python", "sdkVersion": importlib.metadata.version("openai"),
            "profile": profile, "firstDeltaSeconds": None, "usage": None,
            "schemaSha256": hashlib.sha256(dump(body["response_format"]["json_schema"]["schema"]).encode()).hexdigest(), **budget}})
    began = time.monotonic()
    try:
        try:
            client = OpenAI(max_retries=0, timeout=120, default_headers={"Accept-Encoding": "gzip"})
        except (OpenAIError, ValueError) as exc:
            raise ContractError("CHAT_AI_NOT_CONFIGURED" if kind == "chat" else "AI_NOT_CONFIGURED") from exc
        with client as client:
            if stream:
                with client.chat.completions.create(**body) as chunks:
                    consume_stream(chunks, record, began=began)
            else:
                response = client.chat.completions.create(**body)
                record["metadata"]["usage"] = response.usage.model_dump() if response.usage else None
                if len(response.choices) != 1:
                    raise ValueError("expected one model choice")
                choice = response.choices[0]
                record["providerFinishReason"] = choice.finish_reason
                record["providerContent"] = choice.message.content
                record["refusalObserved"] = bool(choice.message.refusal)
                if choice.message.tool_calls:
                    raise ContractError("CHAT_INVALID_AI_RESPONSE" if kind == "chat" else "AI_INVALID_RESPONSE")
                provider_status(choice.finish_reason, refusal=record["refusalObserved"], kind=kind)
                record["response"] = load_json(choice.message.content)
            validate_schema(record["response"], f"{kind}.{profile}")
            if kind == "chat":
                validate_chat(record["response"], transmitted)
                if stream and "".join(record["answerDeltas"]) != record["response"]["answer"]:
                    raise ContractError("CHAT_INVALID_AI_RESPONSE")
    except Exception as exc:
        error = classify_error(exc, kind)
        # INVALID_MODEL_SCHEMA is internal to the schema helper, not an app error code.
        if error.code == "INVALID_MODEL_SCHEMA":
            error = ContractError("CHAT_INVALID_AI_RESPONSE" if kind == "chat" else "AI_INVALID_RESPONSE")
        record["error"] = {"code": error.code, "retryable": error.retryable}
        record["validation"] = {"passed": False, **record["error"]}
        raise error from None
    finally:
        record["metadata"]["seconds"] = round(time.monotonic()-began, 3)
    return record


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("kind",choices=("analysis","chat"));p.add_argument("input");p.add_argument("output")
    p.add_argument("--stream",action="store_true");p.add_argument("--request-only",action="store_true")
    p.add_argument("--profile",choices=("spring-v1","recommended-v1"),default="spring-v1")
    args=p.parse_args()
    target=Path(args.output)
    # Fail before any provider call. Exclusive creation below also protects concurrent runs.
    if target.exists():
        raise ValueError("preserve previous experiment output; use a new path")
    if args.stream and args.kind != "chat":
        p.error("--stream is supported for chat only")
    data=load_json(Path(args.input).read_text());prepared=None
    if args.kind=="analysis":
        prepared=normalize(data["captions"],duration_ms=data["durationMs"],metadata=data.get("metadata"))
        data=prepared["modelInput"]
    prepared_request=request(args.kind,data,args.profile,stream=args.stream)
    body,transmitted,budget=prepared_request
    result={"request":body,"transmittedInput":transmitted,"budget":budget}
    if prepared:result["preprocessing"]=prepared
    target.parent.mkdir(parents=True,exist_ok=True)
    with target.open("x",encoding="utf-8") as handle:
        # Preserve a diagnostic even if the process is interrupted during the provider request.
        handle.write(dump({**result,"status":"IN_PROGRESS"})+"\n")
        handle.flush()
        try:
            if not args.request_only:
                invoke(args.kind,data,stream=args.stream,profile=args.profile,
                       record=result,prepared_request=prepared_request)
                if prepared:
                    group_doc={"segments":[{"segmentId":g["groupId"],"startMs":g["startMs"],"endMs":g["endMs"]} for g in prepared["groups"]]}
                    validate_analysis(result["response"],group_doc,profile=args.profile,require_full_coverage=True)
                    expanded=expand_output(result["response"],prepared)
                    result["springOutput"]=expanded
                    result["mappedTimes"]=validate_analysis(expanded,prepared["originalDocument"],expanded=True,profile=args.profile)
                result["validation"]={"passed":True}
        except Exception as exc:
            error=classify_error(exc,args.kind)
            result["error"]={"code":error.code,"retryable":error.retryable}
            result["validation"]={"passed":False,**result["error"]}
        handle.seek(0)
        handle.write(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
        handle.truncate()
    ok=result.get("validation",{}).get("passed",True)
    print(dump({"output":str(target),"ok":ok}))
    if not ok:sys.exit(1)


if __name__=="__main__":
    try:main()
    except Exception as exc:
        print(dump({"error":getattr(exc,"code",type(exc).__name__)}),file=sys.stderr)
        sys.exit(1)
