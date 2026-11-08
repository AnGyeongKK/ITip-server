package com.gkk.itip.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("itip.worker")
public record AnalysisWorkerProperties(
        @DefaultValue("PT2S") Duration pollDelay,
        @DefaultValue("PT2M") Duration leaseDuration,
        @DefaultValue("PT20S") Duration heartbeatDelay,
        @DefaultValue("PT5S") Duration retryDelay,
        @DefaultValue("1200") int chunkCodePoints) {
    public AnalysisWorkerProperties {
        if (!positive(pollDelay) || !positive(leaseDuration) || !positive(heartbeatDelay)
                || heartbeatDelay.compareTo(leaseDuration) >= 0 || retryDelay == null || retryDelay.isNegative()
                || chunkCodePoints <= 0)
            throw new IllegalArgumentException("invalid worker timing or chunk configuration");
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
