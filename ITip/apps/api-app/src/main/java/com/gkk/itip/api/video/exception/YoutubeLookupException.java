package com.gkk.itip.api.video.exception;

import org.springframework.http.HttpStatus;

public final class YoutubeLookupException extends RuntimeException {

    public enum Failure {
        PLAYLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "재생목록을 찾을 수 없어요."),
        PLAYLIST_NOT_ACCESSIBLE(HttpStatus.FORBIDDEN, "접근할 수 없는 재생목록이에요."),
        YOUTUBE_LOOKUP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "목록을 불러오지 못했어요.");

        private final HttpStatus status;
        private final String message;

        Failure(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private final Failure failure;

    public YoutubeLookupException(Failure failure) {
        super(failure.message);
        this.failure = failure;
    }

    public HttpStatus status() {
        return failure.status;
    }

    public String code() {
        return failure.name();
    }
}
