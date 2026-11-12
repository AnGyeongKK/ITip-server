import json
import unittest
from types import SimpleNamespace as NS
from unittest.mock import MagicMock, patch

from ai_runtime import AIError
from chat_stream import ContentDecoder
import yousum_chat as chat


class DecoderTests(unittest.TestCase):
    def test_every_split_preserves_escapes_and_unicode(self):
        text = '한글 "인용" \\ 경로\n둘째 줄 😀'
        for ascii_only in (True, False):
            raw = json.dumps({"content": text, "sourceIds": ["src-1"], "finishReason": "STOP"}, ensure_ascii=ascii_only)
            for split in range(len(raw) + 1):
                with self.subTest(ascii=ascii_only, split=split):
                    decoder = ContentDecoder()
                    actual = decoder.feed(raw[:split]) + decoder.feed(raw[split:])
                    self.assertEqual(actual, text)
                    self.assertEqual(decoder.finish()["content"], text)

    def test_one_character_chunks_and_no_metadata_leak(self):
        raw = '{"content":"안녕\\n하세요","sourceIds":["secret-source"],"finishReason":"STOP"}'
        decoder = ContentDecoder()
        self.assertEqual("".join(decoder.feed(c) for c in raw), "안녕\n하세요")
        self.assertNotIn("secret", decoder.text)
        decoder.finish()

    def test_malformed_or_incomplete_stream_rejected(self):
        for raw in ('{"content":"unfinished', '{"content":"\\q"}',
                    '{"content":"\\ud800x"}', '{"content":"\\udc00"}',
                    '{"content":"x","content":"y"}', '{"content":"x"} junk'):
            with self.subTest(raw=raw), self.assertRaises((ValueError, AIError)):
                decoder = ContentDecoder()
                decoder.feed(raw)
                decoder.finish()

    def test_stream_limit(self):
        with self.assertRaises(AIError):
            ContentDecoder(max_chars=10).feed('{' + ' ' * 20)


def chunk(content=None, reason=None, refusal=None):
    return NS(model="gpt-6-luna", usage=None, choices=[NS(
        index=0, delta=NS(content=content, refusal=refusal), finish_reason=reason)])


class StreamingCallTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((chat.HERE / "chat_cases.json").read_text())["cases"][0]["input"]
        self.output = {"content": "40분에서 10분으로 줄었어요.", "sourceIds": ["src-1"], "finishReason": "STOP"}

    def run_chunks(self, chunks):
        client = MagicMock()
        stream = client.__enter__.return_value.chat.completions.create.return_value
        stream.__enter__.return_value = iter(chunks)
        with patch("openai.OpenAI", return_value=client), patch("yousum_chat.check_token_budget", return_value=100):
            result = list(chat.generate_stream(self.data, "gpt-6-luna", "low", 4096))
        stream.__exit__.assert_called_once()
        return result

    def test_success_preserves_exact_body(self):
        raw = json.dumps(self.output)
        events = self.run_chunks([*(chunk(raw[i:i+3]) for i in range(0, len(raw), 3)), chunk(reason="stop")])
        content = "".join(e["text"] for e in events if e["type"] == "delta")
        self.assertEqual(content, self.output["content"])
        self.assertEqual(events[-1]["type"], "result")
        self.assertEqual(events[-1]["result"]["answer"]["usedRecordCount"], 1)

    def test_truncation_and_eof_are_not_completion(self):
        for finish in (None, "length", "content_filter"):
            with self.subTest(finish=finish), self.assertRaises(AIError):
                self.run_chunks([chunk(json.dumps(self.output)), chunk(reason=finish)])

    def test_invalid_source_never_yields_valid_result(self):
        self.output["sourceIds"] = ["src-fake"]
        with self.assertRaises(ValueError):
            self.run_chunks([chunk(json.dumps(self.output), "stop")])

    def test_refusal(self):
        with self.assertRaises(AIError):
            self.run_chunks([chunk(refusal="no")])

    def test_cancellation_closes_provider_stream(self):
        client = MagicMock()
        stream = client.__enter__.return_value.chat.completions.create.return_value
        stream.__enter__.return_value = iter([chunk('{"content":"첫 부분'), chunk(' 마지막"}')])
        with patch("openai.OpenAI", return_value=client), patch("yousum_chat.check_token_budget", return_value=100):
            generator = chat.generate_stream(self.data, "gpt-6-luna", "low", 4096)
            self.assertEqual(next(generator), {"type": "delta", "text": "첫 부분"})
            generator.close()
        stream.__exit__.assert_called_once()


if __name__ == "__main__":
    unittest.main()
