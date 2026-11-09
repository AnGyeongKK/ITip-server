package com.gkk.itip.chat.generation;

public class ChatAnswerException extends RuntimeException {
    private final String code;
    private final boolean retryable;
    public ChatAnswerException(String code, boolean retryable) {
        super("chat answer generation failed"); this.code=code; this.retryable=retryable;
    }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
