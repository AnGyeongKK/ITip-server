"""Regression checks for the handoff review. All provider interactions are mocked."""
import copy
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from invocation import run
from invocation.answer_stream import AnswerDecoder
from preprocessing.normalize import normalize, expand_output
from validation.contracts import ContractError, load_json, validate_analysis, validate_chat, validate_chat_input, provider_status


def chunk(text=None, reason=None, *, refusal=None):
    from openai.types.chat import ChatCompletionChunk
    return ChatCompletionChunk(id="fixture", created=0, model="gpt-6-luna", object="chat.completion.chunk",
        choices=[{"index": 0, "delta": {"content": text, "refusal": refusal}, "finish_reason": reason}])


def completion(output, reason="stop"):
    from openai.types.chat import ChatCompletion
    return ChatCompletion(id="fixture", created=0, model="gpt-6-luna", object="chat.completion",
        choices=[{"index": 0, "message": {"role": "assistant", "content": json.dumps(output)}, "finish_reason": reason}])


class Review(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT / "examples/chat.supported.input.json").read_text())
        self.output = {"answer": "40분에서 10분으로 줄었어요.", "citations": [
            {"contextId": "C1", "sectionId": self.data["contexts"][0]["sectionIds"][0]}], "evidenceStatus": "SUPPORTED"}

    def mock_client(self, events):
        client = MagicMock()
        stream = client.__enter__.return_value.chat.completions.create.return_value
        stream.__enter__.return_value = iter(events)
        return client, stream

    def invoke_events(self, events, record):
        client, stream = self.mock_client(events)
        with patch("openai.OpenAI", return_value=client) as factory:
            try:
                return run.invoke("chat", self.data, stream=True, record=record)
            finally:
                stream.__exit__.assert_called_once()
                self.assertEqual(factory.call_args.kwargs["max_retries"], 0)

    def test_stop_and_usage_chunk_keep_exact_answer(self):
        from openai.types.chat import ChatCompletionChunk
        usage = ChatCompletionChunk(id="fixture", created=0, model="gpt-6-luna", object="chat.completion.chunk",
            choices=[], usage={"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15})
        record = self.invoke_events([chunk(json.dumps(self.output)), chunk(reason="stop"), usage], {})
        self.assertEqual("".join(record["answerDeltas"]), self.output["answer"])
        self.assertEqual(record["metadata"]["usage"]["total_tokens"], 15)

    def test_stream_record_contains_exact_sdk_request(self):
        client, _ = self.mock_client([chunk(json.dumps(self.output), "stop")])
        with patch("openai.OpenAI", return_value=client):
            record = run.invoke("chat", self.data, stream=True)
        sent = client.__enter__.return_value.chat.completions.create.call_args.kwargs
        self.assertEqual(record["request"], sent)
        self.assertIs(sent["stream"], True)
        self.assertEqual(sent["stream_options"], {"include_usage": True})
        self.assertTrue(sent["response_format"]["json_schema"]["strict"])

    def test_request_only_stream_records_options_without_call(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "request.json"
            with patch.object(sys, "argv", ["run.py", "chat", str(ROOT / "examples/chat.supported.input.json"),
                    str(target), "--stream", "--request-only"]), patch("openai.OpenAI") as factory, contextlib.redirect_stdout(io.StringIO()):
                run.main()
                factory.assert_not_called()
            record = json.loads(target.read_text())
            self.assertIs(record["request"]["stream"], True)
            self.assertEqual(record["request"]["stream_options"], {"include_usage": True})
        body, _, _ = run.request("chat", self.data)
        self.assertNotIn("stream", body)
        self.assertNotIn("stream_options", body)

    def test_content_after_stop_is_rejected_and_recorded(self):
        record = {}
        with self.assertRaises(ContractError):
            self.invoke_events([chunk(json.dumps(self.output), "stop"), chunk(" ")], record)
        self.assertEqual(record["error"], {"code": "CHAT_INVALID_AI_RESPONSE", "retryable": False})

    def test_eof_is_not_insufficient_evidence(self):
        record = {}
        with self.assertRaises(ContractError):
            self.invoke_events([chunk(json.dumps(self.output))], record)
        self.assertEqual(record["error"]["code"], "CHAT_STREAM_FAILED")
        self.assertTrue(record["answerDeltas"])

    def test_stop_with_truncated_json_is_invalid_response(self):
        record = {}
        with self.assertRaises(ContractError):
            self.invoke_events([chunk('{"answer":"일부 본문', "stop")], record)
        self.assertEqual(record["error"], {"code": "CHAT_INVALID_AI_RESPONSE", "retryable": False})

    def test_length_preserves_partial_text_without_completion(self):
        record = {}
        with self.assertRaises(ContractError):
            self.invoke_events([chunk('{"answer":"일부 본문', "length")], record)
        self.assertEqual(record["error"]["code"], "CHAT_ANSWER_TOO_LONG")
        self.assertEqual("".join(record["answerDeltas"]), "일부 본문")

    def test_bad_citation_keeps_response_for_diagnostics(self):
        self.output["citations"][0]["contextId"] = "C999"
        record = {}
        with self.assertRaises(ContractError):
            self.invoke_events([chunk(json.dumps(self.output), "stop")], record)
        self.assertEqual(record["response"], self.output)
        self.assertFalse(record["validation"]["passed"])

    def test_refusal_is_not_emitted_as_answer(self):
        record = {}
        with self.assertRaises(ContractError):
            self.invoke_events([chunk(refusal="refused")], record)
        self.assertEqual(record["answerDeltas"], [])
        self.assertEqual(record["error"]["code"], "CHAT_AI_REFUSED")

    def test_existing_output_never_calls_model(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "existing.json"
            target.write_text("preserve me")
            with patch.object(sys, "argv", ["run.py", "chat", "not-read.json", str(target)]), patch("openai.OpenAI") as factory:
                with self.assertRaises(ValueError):
                    run.main()
                factory.assert_not_called()
            self.assertEqual(target.read_text(), "preserve me")

    def test_cli_saves_invalid_chat_and_partial_body(self):
        self.output["citations"][0]["contextId"] = "C999"
        client, _ = self.mock_client([chunk(json.dumps(self.output), "stop")])
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "failed.json"
            with patch.object(sys, "argv", ["run.py", "chat", str(ROOT / "examples/chat.supported.input.json"), str(target), "--stream"]), patch("openai.OpenAI", return_value=client), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as exc:
                    run.main()
            self.assertEqual(exc.exception.code, 1)
            record = json.loads(target.read_text())
            self.assertFalse(record["validation"]["passed"])
            self.assertTrue(record["providerChunks"])
            self.assertEqual("".join(record["answerDeltas"]), self.output["answer"])

    def test_budget_relabels_contexts_and_excludes_removed_ids(self):
        self.data["contexts"][0]["contextId"] = "C8"
        second = copy.deepcopy(self.data["contexts"][0]); second["contextId"] = "C9"
        self.data["contexts"].append(second)
        with patch("invocation.run.count_input", side_effect=lambda s, d, o: len(d["contexts"]) * 100):
            kept, meta = run.prepare_chat(self.data, "", {}, budget=100)
        self.assertEqual([c["contextId"] for c in kept["contexts"]], ["C1"])
        self.assertEqual(meta["contextMapping"], [{"contextId": "C1", "inputContextId": "C8"}])
        self.assertEqual(len(self.data["contexts"]), 2)

    def test_token_overflow_keeps_question_intact(self):
        self.data["question"] = "어떤 내용인가요?"
        with patch("invocation.run.count_input", return_value=1000):
            with self.assertRaises(ContractError) as exc:
                run.prepare_chat(self.data, "", {}, budget=10)
        self.assertEqual(exc.exception.code, "CHAT_PROMPT_TOO_LARGE")
        self.assertEqual(self.data["question"], "어떤 내용인가요?")

    def test_malformed_chat_containers_are_rejected_before_call(self):
        cases = [None, {**self.data, "contexts": None}, {**self.data, "history": {}},
                 {**self.data, "contexts": [None]}]
        bad = copy.deepcopy(self.data); bad["contexts"][0]["sectionIds"] = "not-an-array"; cases.append(bad)
        for data in cases:
            with self.subTest(data=data), self.assertRaises(ValueError):
                validate_chat_input(data)

    def test_question_limit_counts_utf16_not_python_characters(self):
        self.data["question"] = "😀" * 2001
        with self.assertRaises(ValueError): validate_chat_input(self.data)

    def test_stream_answer_limit_counts_utf16(self):
        decoder = AnswerDecoder()
        with self.assertRaises(ContractError): decoder.feed('{"answer":"' + "😀" * 32769)

    def test_actual_schema_profile_is_rechecked_after_provider(self):
        saved = json.loads((ROOT / "examples/analysis.recommended.actual.json").read_text())
        output = saved["response"]
        output["sections"] = output["sections"][:1]
        client = MagicMock()
        client.__enter__.return_value.chat.completions.create.return_value = completion(output)
        record = {}
        with patch("openai.OpenAI", return_value=client), self.assertRaises(ContractError):
            run.invoke("analysis", saved["transmittedInput"], profile="recommended-v1", record=record)
        self.assertEqual(record["response"], output)
        self.assertEqual(record["error"]["code"], "AI_INVALID_RESPONSE")

    def test_analysis_input_duration_and_original_snapshot(self):
        data = json.loads((ROOT / "examples/analysis.input.json").read_text())
        prepared = normalize(data["captions"], duration_ms=data["durationMs"])
        self.assertEqual(prepared["modelInput"]["durationMs"], data["durationMs"])
        data["captions"]["segments"][0]["text"] = "changed"
        self.assertNotEqual(prepared["originalDocument"], data["captions"])

    def test_invalid_metadata_is_rejected_and_input_caps_apply(self):
        data = json.loads((ROOT / "examples/analysis.input.json").read_text())
        with self.assertRaises(ValueError): normalize(data["captions"], duration_ms=data["durationMs"], metadata={"tags": "Docker"})
        prepared = normalize(data["captions"], duration_ms=data["durationMs"], metadata={"description": "a" * 1600 + " https://example.com", "tags": ["x" * 290, "y" * 20]})
        self.assertEqual(len(prepared["modelInput"]["description"]), 1500)
        self.assertLessEqual(len(", ".join(prepared["modelInput"]["tags"])), 300)

    def test_group_to_original_expansion_with_multiple_raw_ids(self):
        demo = json.loads((ROOT / "examples/analysis.mapping-demo.json").read_text())
        prepared = demo["prepared"]
        output = json.loads((ROOT / "examples/analysis.recommended.actual.json").read_text())["response"]
        output["summary"]["practicalPoints"] = []
        output["summary"]["mentionedTools"] = []
        output["sections"] = output["sections"][:1]
        s = output["sections"][0]
        s.update(startSegmentId="g000001", endSegmentId="g000002", sourceSegmentIds=["g000001"])
        for name in ("applicationTips", "recommendedTools", "examples"):
            s["detail"][name] = []
        expanded = expand_output(output, prepared)
        self.assertEqual(expanded["sections"][0]["sourceSegmentIds"], prepared["groups"][0]["originalSegmentIds"])
        self.assertEqual(expanded["sections"][0]["endSegmentId"], prepared["groups"][1]["originalSegmentIds"][-1])
        s["startSegmentId"] = "g999999"
        with self.assertRaises(ValueError): expand_output(output, prepared)

    def test_analysis_prefix_middle_and_suffix_gaps_are_rejected(self):
        saved = json.loads((ROOT / "examples/analysis.recommended.actual.json").read_text())
        groups = {"segments": [{"segmentId": g["groupId"], "startMs": g["startMs"], "endMs": g["endMs"]} for g in saved["preprocessing"]["groups"]]}
        for location in ("prefix", "middle", "suffix"):
            output = copy.deepcopy(saved["response"])
            output["summary"]["practicalPoints"] = []; output["summary"]["mentionedTools"] = []
            for s in output["sections"]:
                s["sourceSegmentIds"] = [s["startSegmentId"]]
                for name in ("applicationTips", "recommendedTools"):
                    s["detail"][name] = []
                s["detail"]["examples"] = [{"text": "example", "origin": "SOURCE", "sourceSegmentIds": [s["startSegmentId"]]}]
            if location == "prefix":
                output["sections"][0]["startSegmentId"] = groups["segments"][1]["segmentId"]
                output["sections"][0]["sourceSegmentIds"] = [groups["segments"][1]["segmentId"]]
                output["sections"][0]["detail"]["examples"][0]["sourceSegmentIds"] = [groups["segments"][1]["segmentId"]]
            elif location == "suffix":
                output["sections"][-1]["endSegmentId"] = groups["segments"][-2]["segmentId"]
            else:
                first = output["sections"][0]
                end_index = next(i for i, g in enumerate(groups["segments"]) if g["segmentId"] == first["endSegmentId"])
                first["endSegmentId"] = groups["segments"][end_index-1]["segmentId"]
            with self.subTest(location=location), self.assertRaises(ValueError):
                validate_analysis(output, groups, require_full_coverage=True)

    def test_analysis_errors_do_not_use_chat_codes(self):
        with self.assertRaises(ContractError) as exc: provider_status("length", kind="analysis")
        self.assertEqual(exc.exception.code, "AI_INVALID_RESPONSE")

    def test_http_errors_are_classified_without_provider_message(self):
        import httpx
        from openai import APIStatusError
        for status, retryable in ((401, False), (408, True), (429, True), (500, True)):
            exc = APIStatusError("private provider details", response=httpx.Response(status, request=httpx.Request("POST", "https://example.com")), body=None)
            error = run.classify_error(exc, "analysis")
            self.assertEqual(error.retryable, retryable)
            self.assertNotIn("private", str(error))

    def test_nonfinite_numbers_and_duplicate_fields_rejected(self):
        for text in ('{"x":NaN}', '{"x":Infinity}', '{"x":1e9999}', '{"x":1,"x":2}'):
            with self.subTest(text=text), self.assertRaises(ValueError): load_json(text)

    def test_connection_failure_keeps_partial_body_and_closes_stream(self):
        import httpx
        from openai import APIConnectionError
        def events():
            yield chunk('{"answer":"일부 본문')
            raise APIConnectionError(request=httpx.Request("POST", "https://example.com"))
        record = {}
        with self.assertRaises(ContractError): self.invoke_events(events(), record)
        self.assertEqual(record["error"], {"code": "CHAT_STREAM_FAILED", "retryable": True})
        self.assertEqual("".join(record["answerDeltas"]), "일부 본문")

    def test_invalid_client_configuration_is_not_model_output_error(self):
        from openai import OpenAIError
        record = {}
        with patch("openai.OpenAI", side_effect=OpenAIError("private config")), self.assertRaises(ContractError):
            run.invoke("chat", self.data, record=record)
        self.assertEqual(record["error"]["code"], "CHAT_AI_NOT_CONFIGURED")
        self.assertNotIn("private", json.dumps(record))

    def test_removed_context_is_not_a_citation_candidate(self):
        second = copy.deepcopy(self.data["contexts"][0]); second["contextId"] = "C2"
        self.data["contexts"].append(second)
        with patch("invocation.run.count_input", side_effect=lambda s, d, o: len(d["contexts"]) * 100):
            kept, _ = run.prepare_chat(self.data, "", {}, budget=100)
        self.output["citations"][0]["contextId"] = "C2"
        with self.assertRaises(ContractError): validate_chat(self.output, kept)

    def test_minimum_two_sections_does_not_call_provider_for_one_group(self):
        data = {"videoId": "fixture", "language": "ko", "durationMs": 10000,
                "groups": [{"groupId": "g000001", "text": "한 묶음뿐인 입력"}]}
        with patch("openai.OpenAI") as factory, self.assertRaises(ValueError):
            run.invoke("analysis", data, profile="recommended-v1")
        factory.assert_not_called()


if __name__ == "__main__": unittest.main()
