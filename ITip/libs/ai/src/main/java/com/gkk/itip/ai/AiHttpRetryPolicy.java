package com.gkk.itip.ai;

/** Worker·질문 임베딩·답변 생성에서 실제로 공유하는 HTTP 오류 정책. */
public final class AiHttpRetryPolicy {
    private AiHttpRetryPolicy() {}
    public static boolean retryable(int status) { return status==408 || status==429 || status>=500; }
}
