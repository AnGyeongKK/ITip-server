package com.gkk.itip.chat.stream;

import java.time.Duration;

public final class ChatLimits {
    private ChatLimits() {}
    public static final int CONCURRENT_STREAMS=4;
    public static final int MAX_QUESTION_CHARS=4000;
    public static final int MAX_ANSWER_CHARS=65536;
    public static final int MAX_SOURCES=20;
    public static final int MAX_HISTORY_CHARS=32000;
    public static final Duration STREAM_DURATION=Duration.ofSeconds(120);
    public static final Duration IDLE_TIMEOUT=Duration.ofSeconds(30);
    public static final Duration GENERATION_LEASE=STREAM_DURATION.plusSeconds(30);
    public static final long MVC_TIMEOUT_MS=STREAM_DURATION.plusSeconds(5).toMillis();
}
