package com.gkk.itip.common.exception;

public enum ErrorCode {

    INVALID_REQUEST("잘못된 요청이에요."),
    RESOURCE_NOT_FOUND("요청한 항목을 찾을 수 없어요."),
    IDEMPOTENCY_CONFLICT("같은 요청 키로 다른 요청을 보낼 수 없어요."),
    STATE_CONFLICT("현재 상태에서는 요청을 처리할 수 없어요."),
    VALIDATION_FAILED("요청이 처리 조건을 충족하지 않아요."),
    INTERNAL_ERROR("요청을 처리하는 중 오류가 발생했어요.");

    private final String message;

    ErrorCode(String message) {
        this.message = message;
    }

    public String getCode() {
        return name();
    }

    public String getMessage() {
        return message;
    }
}
