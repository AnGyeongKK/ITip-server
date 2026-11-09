package com.gkk.itip.worker.analysis.caption;

public class InvalidCaptionException extends RuntimeException {
    private final String code;

    public InvalidCaptionException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() { return code; }
}
