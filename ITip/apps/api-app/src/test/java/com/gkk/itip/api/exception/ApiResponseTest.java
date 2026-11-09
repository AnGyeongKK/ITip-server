package com.gkk.itip.api.exception;

import com.gkk.itip.common.exception.ErrorCode;
import com.gkk.itip.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void successWithDataKeepsNestedNullFields() {
        JsonNode response = json(ApiResponse.success(new CursorPage(List.of(), null, false)));

        assertThat(response.get("code").asString()).isEqualTo("OK");
        assertThat(response.get("message").asString()).isEqualTo("요청이 완료됐어요.");
        assertThat(response.get("data").get("items").isArray()).isTrue();
        assertThat(response.get("data").has("nextCursor")).isTrue();
        assertThat(response.get("data").get("nextCursor").isNull()).isTrue();
        assertThat(response.get("data").get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void successWithoutDataOmitsOnlyTopLevelData() {
        JsonNode response = json(ApiResponse.success("요청을 처리했어요."));

        assertThat(response.size()).isEqualTo(2);
        assertThat(response.get("code").asString()).isEqualTo("OK");
        assertThat(response.get("message").asString()).isEqualTo("요청을 처리했어요.");
        assertThat(response.has("data")).isFalse();
    }

    @Test
    void successCanOverrideMessageAndReturnStringData() {
        JsonNode response = json(ApiResponse.success("saved", "저장했어요."));

        assertThat(response.get("message").asString()).isEqualTo("저장했어요.");
        assertThat(response.get("data").asString()).isEqualTo("saved");
    }

    @Test
    void failureUsesErrorCodeAndOmitsData() {
        JsonNode response = json(ApiResponse.fail(ErrorCode.RESOURCE_NOT_FOUND));

        assertThat(response.size()).isEqualTo(2);
        assertThat(response.get("code").asString()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.get("message").asString()).isEqualTo("요청한 항목을 찾을 수 없어요.");
        assertThat(response.has("data")).isFalse();
    }

    @Test
    void failureCanOverrideMessageWithoutAddingData() {
        JsonNode response = json(ApiResponse.fail(ErrorCode.STATE_CONFLICT, "이미 처리 중이에요."));

        assertThat(response.get("code").asString()).isEqualTo("STATE_CONFLICT");
        assertThat(response.get("message").asString()).isEqualTo("이미 처리 중이에요.");
        assertThat(response.has("data")).isFalse();
    }

    private JsonNode json(ApiResponse<?> response) {
        return jsonMapper.readTree(jsonMapper.writeValueAsString(response));
    }

    record CursorPage(List<String> items, String nextCursor, boolean hasNext) {
    }
}
