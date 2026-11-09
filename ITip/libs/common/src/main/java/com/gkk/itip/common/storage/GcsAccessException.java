package com.gkk.itip.common.storage;

public class GcsAccessException extends RuntimeException {
    public enum Reason {
        OBJECT_NOT_FOUND, OBJECT_VERSION_CHANGED, CAPTION_TOO_LARGE,
        STORAGE_NOT_CONFIGURED, STORAGE_UNAVAILABLE, INVALID_OBJECT_METADATA
    }
    private final Reason reason;
    private final boolean retryable;

    public GcsAccessException(Reason reason, boolean retryable) {
        super(reason.name());
        this.reason = reason;
        this.retryable = retryable;
    }
    public Reason getReason() { return reason; }
    public boolean isRetryable() { return retryable; }
}
