package com.gkk.itip.api.analysis.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record ReportCollectionFailureRequest(@NotNull @Valid CollectionFailure collectionFailure) {
    public record CollectionFailure(@NotNull Code code) {}

    public enum Code {
        KOREAN_CAPTION_NOT_FOUND("한국어 자막을 찾을 수 없어요."),
        VIDEO_UNAVAILABLE("영상을 가져올 수 없어요."),
        DURATION_EXCEEDED("60분 이내 영상만 분석할 수 있어요."),
        COLLECTION_RATE_LIMITED("자막 수집이 제한됐어요. 추가 수집을 중단해 주세요."),
        UPLOAD_FAILED("자막을 업로드하지 못했어요.");

        private final String message;
        Code(String message) { this.message = message; }
        public String message() { return message; }
    }
}
