package com.gkk.itip.api.exception;

import com.gkk.itip.common.web.ApiExceptionHandler;

import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.exception.ErrorCode;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @CsvSource({
            "INVALID_REQUEST,400",
            "RESOURCE_NOT_FOUND,404",
            "IDEMPOTENCY_CONFLICT,409",
            "STATE_CONFLICT,409",
            "UPLOAD_NOT_READY,409",
            "JOB_NOT_RETRYABLE,409",
            "CAPTION_REUPLOAD_REQUIRED,410",
            "CAPTION_TOO_LARGE,413",
            "STORAGE_UNAVAILABLE,503",
            "VALIDATION_FAILED,422",
            "INTERNAL_ERROR,500"
    })
    void businessErrorsUseTheirHttpStatus(String code, int httpStatus) throws Exception {
        mockMvc.perform(get("/probe/business/{code}", code))
                .andExpect(status().is(httpStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.data").doesNotHaveJsonPath());
    }

    @Test
    void businessErrorKeepsItsCustomMessage() throws Exception {
        mockMvc.perform(get("/probe/custom-message"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.message").value("이미 처리 중이에요."));
    }

    @Test
    void malformedJsonReturnsInvalidRequestWithoutParserDetails() throws Exception {
        mockMvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": secret-input"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청이에요.\"}"))
                .andExpect(jsonPath("$.data").doesNotHaveJsonPath());
    }

    @Test
    void invalidBodyReturnsInvalidRequestWithoutRejectedValue() throws Exception {
        mockMvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"INVALID_REQUEST\",\"message\":\"잘못된 요청이에요.\"}"))
                .andExpect(jsonPath("$.data").doesNotHaveJsonPath());
    }

    @Test
    void invalidPathConstraintReturnsInvalidRequest() throws Exception {
        mockMvc.perform(get("/probe/path/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void invalidPathTypeReturnsInvalidRequest() throws Exception {
        mockMvc.perform(get("/probe/path/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingRequiredQueryReturnsInvalidRequest() throws Exception {
        mockMvc.perform(get("/probe/query"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedMethodKeepsFrameworkStatusAndUsesApiWrapper() throws Exception {
        mockMvc.perform(post("/probe/query"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotHaveJsonPath());
    }

    @Test
    void unexpectedFailureReturnsGenericServerError() throws Exception {
        mockMvc.perform(get("/probe/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":\"INTERNAL_ERROR\",\"message\":\"요청을 처리하는 중 오류가 발생했어요.\"}"))
                .andExpect(jsonPath("$.data").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.stackTrace").doesNotHaveJsonPath());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/business/{code}")
        ApiResponse<Void> business(@PathVariable("code") String code) {
            throw new BusinessException(ErrorCode.valueOf(code));
        }

        @GetMapping("/probe/custom-message")
        ApiResponse<Void> customMessage() {
            throw new BusinessException(ErrorCode.STATE_CONFLICT, "이미 처리 중이에요.");
        }

        @PostMapping("/probe/body")
        ApiResponse<BodyInput> body(@Valid @RequestBody BodyInput input) {
            return ApiResponse.success(input);
        }

        @GetMapping("/probe/path/{id}")
        ApiResponse<Long> path(@PathVariable("id") @Min(1) long id) {
            return ApiResponse.success(id);
        }

        @GetMapping("/probe/query")
        ApiResponse<Long> query(@RequestParam("id") long id) {
            return ApiResponse.success(id);
        }

        @GetMapping("/probe/unexpected")
        ApiResponse<Void> unexpected() {
            throw new IllegalStateException("secret-token and request payload");
        }
    }

    record BodyInput(@NotBlank String title) {
    }
}
