"""Incrementally decode the first content JSON string, including split surrogate pairs."""
import re

from ai_runtime import AIError, load_json


class ContentDecoder:
    def __init__(self, max_chars=200_000):
        self.raw = ""
        self.max_chars = max_chars
        self.started = self.closed = self.escape = False
        self.unicode_digits = None
        self.high_surrogate = None
        self.text = ""

    def feed(self, fragment):
        if not isinstance(fragment, str):
            raise AIError("INVALID_STREAM_FRAGMENT")
        self.raw += fragment
        if len(self.raw) > self.max_chars:
            raise AIError("OUTPUT_LIMIT_EXCEEDED")
        if not self.started:
            match = re.match(r'^\s*\{\s*"content"\s*:\s*"', self.raw)
            if match is None:
                if len(self.raw) > 256:
                    raise AIError("INVALID_STREAM_PREFIX")
                return ""
            self.started = True
            fragment = self.raw[match.end():]
        emitted = []
        for char in fragment:
            if self.closed:
                break
            if self.unicode_digits is not None:
                if char not in "0123456789abcdefABCDEF":
                    raise AIError("INVALID_JSON_ESCAPE")
                self.unicode_digits += char
                if len(self.unicode_digits) == 4:
                    value = int(self.unicode_digits, 16)
                    self.unicode_digits = None
                    if 0xD800 <= value <= 0xDBFF:
                        if self.high_surrogate is not None:
                            raise AIError("INVALID_JSON_SURROGATE")
                        self.high_surrogate = value
                    elif 0xDC00 <= value <= 0xDFFF:
                        if self.high_surrogate is None:
                            raise AIError("INVALID_JSON_SURROGATE")
                        emitted.append(chr(0x10000 + ((self.high_surrogate - 0xD800) << 10) + value - 0xDC00))
                        self.high_surrogate = None
                    else:
                        if self.high_surrogate is not None:
                            raise AIError("INVALID_JSON_SURROGATE")
                        emitted.append(chr(value))
            elif self.escape:
                self.escape = False
                if char == "u":
                    self.unicode_digits = ""
                else:
                    mapping = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f",
                               "n": "\n", "r": "\r", "t": "\t"}
                    if char not in mapping or self.high_surrogate is not None:
                        raise AIError("INVALID_JSON_ESCAPE")
                    emitted.append(mapping[char])
            elif char == "\\":
                self.escape = True
            elif char == '"':
                if self.high_surrogate is not None:
                    raise AIError("INVALID_JSON_SURROGATE")
                self.closed = True
            elif ord(char) < 32 or 0xD800 <= ord(char) <= 0xDFFF or self.high_surrogate is not None:
                raise AIError("INVALID_JSON_STRING")
            else:
                emitted.append(char)
        delta = "".join(emitted)
        self.text += delta
        return delta

    def finish(self):
        if not self.closed or self.escape or self.unicode_digits is not None or self.high_surrogate is not None:
            raise AIError("INCOMPLETE_MODEL_STREAM", retryable=True)
        output = load_json(self.raw)
        if not isinstance(output, dict) or output.get("content") != self.text:
            raise AIError("STREAM_CONTENT_MISMATCH")
        return output
