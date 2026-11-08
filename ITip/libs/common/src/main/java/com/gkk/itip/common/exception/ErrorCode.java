package com.gkk.itip.common.exception;

public enum ErrorCode {

    INVALID_REQUEST("잘못된 요청이에요."),
    INVALID_CURSOR("목록을 새로 불러와 주세요."),
    EMPTY_CHAT_SCOPE("질문할 분석 기록이 없어요."),
    RESOURCE_NOT_FOUND("요청한 항목을 찾을 수 없어요."),
    IDEMPOTENCY_CONFLICT("같은 요청 키로 다른 요청을 보낼 수 없어요."),
    STATE_CONFLICT("현재 상태에서는 요청을 처리할 수 없어요."),
    ANALYSIS_NOT_READY("아직 분석 중이에요."),
    UPLOAD_NOT_READY("업로드한 자막을 확인할 수 없어요. 업로드 상태를 확인해 주세요."),
    JOB_NOT_RETRYABLE("현재 분석 작업은 다시 시도할 수 없어요."),
    CAPTION_REUPLOAD_REQUIRED("원본 자막을 찾을 수 없어요. 자막을 다시 업로드해 주세요."),
    CAPTION_TOO_LARGE("자막 파일의 허용 용량을 초과했어요."),
    STORAGE_UNAVAILABLE("자막 저장소에 연결할 수 없어요. 잠시 후 다시 시도해 주세요."),
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
