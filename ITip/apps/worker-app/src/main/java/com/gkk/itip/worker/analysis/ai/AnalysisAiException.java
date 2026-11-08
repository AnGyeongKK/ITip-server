package com.gkk.itip.worker.analysis.ai;

/** 공급자 응답·원문을 포함하지 않는 Worker용 실패 정보. */
public class AnalysisAiException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public AnalysisAiException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
