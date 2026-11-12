package com.gkk.itip.worker.analysis.caption;

import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

@Component
public class CaptionParser {
    private final JsonMapper mapper;

    public CaptionParser(JsonMapper mapper) { this.mapper = mapper; }

    public CaptionDocument parse(byte[] bytes, String expectedVideoId, long durationMs, long maxBytes) {
        if (bytes == null || bytes.length == 0) throw invalid("CAPTION_INVALID_FORMAT");
        if (bytes.length > maxBytes) throw invalid("CAPTION_TOO_LARGE");
        if (durationMs <= 0) throw invalid("VIDEO_METADATA_REQUIRED");
        if (durationMs > 3_600_000L) throw invalid("DURATION_EXCEEDED");
        JsonNode root;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        } catch (CharacterCodingException | tools.jackson.core.JacksonException exception) {
            throw invalid("CAPTION_INVALID_FORMAT");
        }
        if (root == null || !root.isObject()) throw invalid("CAPTION_INVALID_FORMAT");
        String videoId = requiredText(root, "videoId");
        if (!expectedVideoId.equals(videoId)) throw invalid("UPLOAD_VIDEO_MISMATCH");
        String language = requiredText(root, "language");
        if (!"ko".equals(language)) throw invalid("KOREAN_CAPTION_NOT_FOUND");
        JsonNode segments = root.path("segments");
        if (!segments.isArray() || segments.isEmpty()) throw invalid("CAPTION_INVALID_FORMAT");
        var validated = new ArrayList<CaptionDocument.Segment>();
        long previousStart = -1;
        for (JsonNode segment : segments) {
            long start = requiredLong(segment, "startMs");
            long end = requiredLong(segment, "endMs");
            String text = requiredText(segment, "text");
            if (start < 0 || start >= end || end > durationMs || start < previousStart)
                throw invalid("CAPTION_INVALID_RANGE");
            // 자동 자막의 겹침과 원문은 그대로 보관한다. 시작 시간만 순서대로 요구한다.
            validated.add(new CaptionDocument.Segment("s%06d".formatted(validated.size() + 1), start, end, text));
            previousStart = start;
        }
        return new CaptionDocument(videoId, language, validated);
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asString().isBlank()) throw invalid("CAPTION_INVALID_FORMAT");
        return value.asString();
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("CAPTION_INVALID_FORMAT");
        return value.asLong();
    }

    private InvalidCaptionException invalid(String code) { return new InvalidCaptionException(code); }
}
