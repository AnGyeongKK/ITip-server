package com.gkk.itip.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gkk.itip.common.exception.ErrorCode;

public record ApiResponse<T>(String code, String message, @JsonInclude(JsonInclude.Include.NON_NULL) T data) {

    public static <T> ApiResponse<T> success(T data) {
        return success(data, "요청이 완료됐어요.");
    }

    public static ApiResponse<Void> success(String message) {
        return success(null, message);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>("OK", message, data);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }
}
