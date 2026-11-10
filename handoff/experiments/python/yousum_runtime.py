#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# dependencies = ["openai>=1.60", "jsonschema>=4", "tiktoken>=0.8"]
# ///
"""YouSum AI reference CLI: caption preparation, metadata gate, strict analysis, handoff export."""
import argparse
import hashlib
import json
import os
import shutil
import sys
import time
from pathlib import Path

from ai_runtime import (AIError, check_token_budget, gate_action, load_json, prepare_captions,
                        validate_analysis_input, validate_analysis_output)

HERE = Path(__file__).resolve().parent


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


def gate_request(video):
    from yousum_ai import gate_message

    if not isinstance(video, dict) or not isinstance(video.get("title"), str) or not video["title"].strip():
        raise AIError("INVALID_VIDEO_METADATA")
    for key in ("channel", "category", "description"):
        if not isinstance(video.get(key, ""), str):
            raise AIError("INVALID_VIDEO_METADATA")
    tags = video.get("tags", [])
    if not isinstance(tags, list) or any(not isinstance(t, str) for t in tags):
        raise AIError("INVALID_VIDEO_METADATA")
    schema = load_json((HERE / "gate.schema.json").read_text())
    return [{"role": "system", "content": (HERE / "gate_prompt.md").read_text()},
            {"role": "user", "content": gate_message(video)}], schema


def analysis_request(data):
    from yousum_ai import SCHEMA, PROMPT, build_user_message

    validate_analysis_input(data)
    return [{"role": "system", "content": PROMPT},
            {"role": "user", "content": build_user_message(data)}], SCHEMA


def complete(messages, schema, model, effort, max_tokens, timeout, max_input_tokens):
    from jsonschema import Draft202012Validator
    from openai import OpenAI

    estimate = check_token_budget(messages, schema, max_input_tokens)
    began = time.monotonic()
    with OpenAI(max_retries=0, timeout=timeout, default_headers={"Accept-Encoding": "gzip"}) as client:
        response = client.chat.completions.create(
            model=model, reasoning_effort=effort, max_completion_tokens=max_tokens,
            messages=messages, response_format={"type": "json_schema", "json_schema": {
                "name": "yousum_result", "strict": True, "schema": schema}},
        )
    choice = response.choices[0]
    if choice.message.refusal:
        raise AIError("MODEL_REFUSAL")
    if choice.finish_reason != "stop":
        raise AIError("MODEL_OUTPUT_INCOMPLETE", retryable=True)
    output = load_json(choice.message.content)
    if list(Draft202012Validator(schema).iter_errors(output)):
        raise AIError("INVALID_MODEL_SCHEMA", retryable=True)
    version = hashlib.sha256((messages[0]["content"] + json.dumps(schema)).encode()).hexdigest()[:8]
    return output, {"model": response.model, "effort": effort, "promptVersion": version,
                    "seconds": round(time.monotonic() - began, 2), "estimatedInputTokens": estimate,
                    "usage": response.usage.model_dump() if response.usage else None}


def analyze(data, model="gpt-6-luna", effort="low", max_attempts=3):
    """Local reference only. Backend must resume stages rather than nesting retries around this."""
    if type(max_attempts) is not int or not 1 <= max_attempts <= 3:
        raise AIError("INVALID_ATTEMPT_LIMIT")
    messages, schema = analysis_request(data)
    attempts = []
    for number in range(1, max_attempts + 1):
        try:
            output, meta = complete(messages, schema, model, effort, 16000, 540, 30000)
            errors, warnings, mapped = validate_analysis_output(data, output)
            if errors:
                attempts.append({"attempt": number, "code": "INVALID_ANALYSIS_RESULT", "errors": errors,
                                 "meta": meta, "modelOutput": output})
                continue
            attempts.append({"attempt": number, "code": "OK", **meta})
            return {"modelOutput": output, "mappedSections": mapped, "warnings": warnings,
                    "attempts": attempts, "meta": meta}
        except AIError as exc:
            attempts.append({"attempt": number, "code": exc.code})
            if not exc.retryable:
                raise
        # Provider/network failures are surfaced to the backend; no hidden automatic retry layer.
    return {"error": {"code": "ANALYSIS_ATTEMPTS_EXHAUSTED", "retryable": False}, "attempts": attempts}


def export_handoff(destination):
    from yousum_chat import VERSION

    destination = Path(destination)
    if destination.exists():
        raise AIError("HANDOFF_DESTINATION_EXISTS")
    names = ("analysis_prompt.md", "analysis.schema.json", "gate_prompt.md", "gate.schema.json",
             "chat_prompt.md", "chat.schema.json", "ai_runtime.py", "chat_stream.py", "yousum_ai.py",
             "yousum_chat.py", "yousum_runtime.py", "P0.md", "CHAT.md", "README.md",
             "pyproject.toml", "uv.lock", "requirements.lock", "Dockerfile", ".dockerignore",
             "compose.yaml", ".env.example", "chat_cases.json", "test_yousum_chat.py",
             "test_ai_runtime.py", "test_chat_stream.py", "IMPLEMENTATION.md")
    for name in names:
        if not (HERE / name).is_file():
            raise AIError("MISSING_HANDOFF_FILE")
    destination.mkdir(parents=True)
    hashes = {}
    for name in names:
        shutil.copyfile(HERE / name, destination / name)
        hashes[name] = hashlib.sha256((destination / name).read_bytes()).hexdigest()
    write_json(destination / "manifest.json", {
        "chatPromptVersion": VERSION, "filesSha256": hashes,
        "policy": {"NOT_IT": "REJECT", "UNSURE": "CONFIRM_REQUIRED", "recommendations": "DEFERRED"},
        "models": {"analysis": "gpt-6-luna", "gate": "gpt-6-luna", "chat": "gpt-6-luna", "effort": "low"},
    })
    return {"destination": str(destination), "files": len(hashes)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("upload"), prepare.add_argument("metadata"), prepare.add_argument("out")
    for name in ("gate", "analyze", "request-gate", "request-analysis"):
        cmd = commands.add_parser(name)
        cmd.add_argument("input"), cmd.add_argument("out")
        cmd.add_argument("--model", default=os.getenv("ANALYSIS_MODEL", "gpt-6-luna"))
        cmd.add_argument("--effort", default="low")
        if name == "gate":
            cmd.add_argument("--confirmed", action="store_true")
        if name == "analyze":
            cmd.add_argument("--attempts", type=int, default=3)
    valid = commands.add_parser("validate-analysis")
    valid.add_argument("input"), valid.add_argument("output")
    export = commands.add_parser("export")
    export.add_argument("destination")
    args = parser.parse_args()
    try:
        if args.command == "export":
            print(json.dumps(export_handoff(args.destination)))
            return
        if args.command == "prepare":
            result = prepare_captions(load_json(Path(args.upload).read_text()),
                                      load_json(Path(args.metadata).read_text()))
        else:
            data = load_json(Path(args.input).read_text())
            if args.command == "validate-analysis":
                output = load_json(Path(args.output).read_text())
                errors, warnings, mapped = validate_analysis_output(data, output)
                print(json.dumps({"errors": errors, "warnings": warnings, "mappedSections": mapped}, ensure_ascii=False))
                sys.exit(bool(errors))
            request, schema = gate_request(data) if "gate" in args.command else analysis_request(data)
            if args.command.startswith("request-"):
                result = {"messages": request, "schema": schema}
            elif args.command == "gate":
                output, meta = complete(request, schema, args.model, args.effort, 2000, 30, 6000)
                if not output["reason"].strip():
                    raise AIError("EMPTY_GATE_REASON")
                result = {**output, "action": gate_action(output["verdict"], args.confirmed), "meta": meta}
            else:
                result = analyze(data, args.model, args.effort, args.attempts)
        write_json(args.out, result)
        print(json.dumps({"output": args.out, "ok": "error" not in result}, ensure_ascii=False))
        if "error" in result:
            sys.exit(1)
    except AIError as exc:
        print(json.dumps({"error": {"code": exc.code, "retryable": exc.retryable}}), file=sys.stderr)
        sys.exit(1)
    except Exception as exc:
        print(json.dumps({"error": {"code": type(exc).__name__, "retryable": False}}), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
