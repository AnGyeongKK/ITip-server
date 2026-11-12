package com.gkk.itip.chat.retrieval;

/** 공급자 응답·키·원문을 앱이나 로그에 노출하지 않는 검색 실패. */
public class ChatRetrievalException extends RuntimeException {
    private final boolean retryable;
    public ChatRetrievalException(boolean retryable) { super("chat retrieval failed"); this.retryable = retryable; }
    public boolean retryable() { return retryable; }
}
