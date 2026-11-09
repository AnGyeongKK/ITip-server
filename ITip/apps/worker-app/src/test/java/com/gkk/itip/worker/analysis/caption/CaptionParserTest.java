package com.gkk.itip.worker.analysis.caption;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class CaptionParserTest {
    private final CaptionParser parser = new CaptionParser(JsonMapper.builder().build());
    private static final String VIDEO = "dQw4w9WgXcQ";
    private static final String VALID = """
            {"videoId":"dQw4w9WgXcQ","language":"ko","segments":[
              {"startMs":0,"endMs":3000,"text":" 첫 번째 자막 "},
              {"startMs":2000,"endMs":4000,"text":"겹치는 자동 자막"}]}
            """;

    @Test void preservesTextAndOverlapsWithStableSegmentIds() {
        var result = parse(VALID);
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().getFirst().segmentId()).isEqualTo("s000001");
        assertThat(result.segments().getFirst().text()).isEqualTo(" 첫 번째 자막 ");
        assertThat(result.segments().get(1).startMs()).isEqualTo(2000);
        assertThat(parse(VALID)).isEqualTo(result);
    }

    @Test void validatesActualBytesAndUtf8RatherThanDeclaredSize() {
        byte[] bytes = VALID.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parser.parse(bytes, VIDEO, 5000, bytes.length - 1))
                .isInstanceOf(InvalidCaptionException.class).hasMessageContaining("CAPTION_TOO_LARGE");
        assertThatThrownBy(() -> parser.parse(new byte[]{(byte)0xc3, 0x28}, VIDEO, 5000, 100))
                .isInstanceOf(InvalidCaptionException.class).hasMessageContaining("CAPTION_INVALID_FORMAT");
    }

    @Test void rejectsUnsupportedLanguageAndWrongVideo() {
        rejects(VALID.replace("\"ko\"", "\"en\""), "KOREAN_CAPTION_NOT_FOUND");
        rejects(VALID.replace(VIDEO, "9bZkp7q19f0"), "UPLOAD_VIDEO_MISMATCH");
    }

    @Test void rejectsMissingFieldsEmptyTextAndEmptySegments() {
        rejects("{}", "CAPTION_INVALID_FORMAT");
        rejects(VALID.replace("\"startMs\":0,", ""), "CAPTION_INVALID_FORMAT");
        rejects(VALID.replace(" 첫 번째 자막 ", "  "), "CAPTION_INVALID_FORMAT");
        rejects("{\"videoId\":\""+VIDEO+"\",\"language\":\"ko\",\"segments\":[]}", "CAPTION_INVALID_FORMAT");
    }

    @Test void rejectsReversedOutOfBoundsAndUnorderedTimes() {
        rejects(VALID.replace("\"endMs\":3000", "\"endMs\":0"), "CAPTION_INVALID_RANGE");
        rejects(VALID.replace("\"endMs\":4000", "\"endMs\":6000"), "CAPTION_INVALID_RANGE");
        rejects(VALID.replace("\"startMs\":0", "\"startMs\":-1"), "CAPTION_INVALID_RANGE");
        rejects(VALID.replace("\"startMs\":0", "\"startMs\":2500"), "CAPTION_INVALID_RANGE");
    }

    @Test void requiresRealPositiveDurationAndAllowsExactlySixtyMinutes() {
        byte[] bytes = VALID.getBytes(StandardCharsets.UTF_8);
        assertThatCode(() -> parser.parse(bytes, VIDEO, 3_600_000L, 10000)).doesNotThrowAnyException();
        assertThatThrownBy(() -> parser.parse(bytes, VIDEO, 3_600_001L, 10000))
                .isInstanceOf(InvalidCaptionException.class).hasMessageContaining("DURATION_EXCEEDED");
        assertThatThrownBy(() -> parser.parse(bytes, VIDEO, 0, 10000))
                .isInstanceOf(InvalidCaptionException.class).hasMessageContaining("VIDEO_METADATA_REQUIRED");
    }

    @Test void rejectsNonIntegerTimesAndTrailingJson() {
        rejects(VALID.replace("\"startMs\":0", "\"startMs\":0.1"), "CAPTION_INVALID_FORMAT");
        rejects(VALID + " {}", "CAPTION_INVALID_FORMAT");
    }

    private CaptionDocument parse(String json) {
        return parser.parse(json.getBytes(StandardCharsets.UTF_8), VIDEO, 5000L, 10000);
    }
    private void rejects(String json, String code) {
        assertThatThrownBy(() -> parse(json)).isInstanceOf(InvalidCaptionException.class).hasMessageContaining(code);
    }
}
