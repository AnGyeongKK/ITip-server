"""Incrementally decode answer; do not emit JSON syntax or citation metadata."""
import re

from validation.contracts import ContractError as AIError, load_json


class AnswerDecoder:
    def __init__(self, max_chars=65536*6+16384):
        self.raw = ""
        self.max_chars = max_chars
        self.started = self.closed = self.escape = False
        self.unicode_digits = None
        self.high_surrogate = None
        self.text = ""
        self.utf16_units = 0

    def feed(self, fragment):
        if not isinstance(fragment, str):
            raise AIError("CHAT_INVALID_AI_RESPONSE")
        self.raw += fragment
        if len(self.raw) > self.max_chars:
            raise AIError("CHAT_INVALID_AI_RESPONSE")
        if not self.started:
            match = re.match(r'^\s*\{\s*"answer"\s*:\s*"', self.raw)
            if match is None:
                if len(self.raw) > 256:
                    raise AIError("CHAT_INVALID_AI_RESPONSE")
                return ""
            self.started = True
            fragment = self.raw[match.end():]
        emitted = []
        for char in fragment:
            if self.closed:
                break
            if self.unicode_digits is not None:
                if char not in "0123456789abcdefABCDEF":
                    raise AIError("CHAT_INVALID_AI_RESPONSE")
                self.unicode_digits += char
                if len(self.unicode_digits) == 4:
                    value = int(self.unicode_digits, 16)
                    self.unicode_digits = None
                    if 0xD800 <= value <= 0xDBFF:
                        if self.high_surrogate is not None:
                            raise AIError("CHAT_INVALID_AI_RESPONSE")
                        self.high_surrogate = value
                    elif 0xDC00 <= value <= 0xDFFF:
                        if self.high_surrogate is None:
                            raise AIError("CHAT_INVALID_AI_RESPONSE")
                        emitted.append(chr(0x10000 + ((self.high_surrogate - 0xD800) << 10) + value - 0xDC00))
                        self.high_surrogate = None
                    else:
                        if self.high_surrogate is not None:
                            raise AIError("CHAT_INVALID_AI_RESPONSE")
                        emitted.append(chr(value))
            elif self.escape:
                self.escape = False
                if char == "u":
                    self.unicode_digits = ""
                else:
                    mapping = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f",
                               "n": "\n", "r": "\r", "t": "\t"}
                    if char not in mapping or self.high_surrogate is not None:
                        raise AIError("CHAT_INVALID_AI_RESPONSE")
                    emitted.append(mapping[char])
            elif char == "\\":
                self.escape = True
            elif char == '"':
                if self.high_surrogate is not None:
                    raise AIError("CHAT_INVALID_AI_RESPONSE")
                self.closed = True
            elif ord(char) < 32 or 0xD800 <= ord(char) <= 0xDFFF or self.high_surrogate is not None:
                raise AIError("CHAT_INVALID_AI_RESPONSE")
            else:
                emitted.append(char)
        delta = "".join(emitted)
        self.utf16_units += len(delta.encode("utf-16-le")) // 2
        if self.utf16_units > 65536:
            raise AIError("CHAT_INVALID_AI_RESPONSE")
        self.text += delta
        return delta

    def finish(self):
        if not self.closed or self.escape or self.unicode_digits is not None or self.high_surrogate is not None:
            raise AIError("CHAT_INVALID_AI_RESPONSE")
        try:
            output = load_json(self.raw)
        except (ValueError, TypeError) as exc:
            raise AIError("CHAT_INVALID_AI_RESPONSE") from exc
        if not isinstance(output, dict) or output.get("answer") != self.text:
            raise AIError("CHAT_INVALID_AI_RESPONSE")
        return output
