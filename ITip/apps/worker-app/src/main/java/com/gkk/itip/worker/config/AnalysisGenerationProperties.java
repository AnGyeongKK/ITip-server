package com.gkk.itip.worker.config;

import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("itip.analysis.generation")
public record AnalysisGenerationProperties(
        @DefaultValue("false") boolean enabled, @DefaultValue("") String model,
        @DefaultValue("") String tokenEncoding, @DefaultValue("0") int contextTokens,
        @DefaultValue("30000") int inputTokenBudget, @DefaultValue("16000") int totalOutputTokens,
        @DefaultValue("1024") int planOutputTokens, @DefaultValue("4096") int detailOutputTokens,
        @DefaultValue("3072") int summaryOutputTokens, @DefaultValue("3") int detailConcurrency) {
    public AnalysisGenerationProperties {
        if (inputTokenBudget < 1 || planOutputTokens < 1 || detailOutputTokens < 1 || summaryOutputTokens < 1
                || (long) planOutputTokens + summaryOutputTokens >= totalOutputTokens || detailOutputTokens > totalOutputTokens
                || detailConcurrency < 1 || detailConcurrency > 3)
            throw new IllegalArgumentException("invalid analysis token budget or concurrency");
        if (enabled && (model.isBlank() || tokenEncoding.isBlank()
                || (long) inputTokenBudget + Math.max(detailOutputTokens, Math.max(planOutputTokens, summaryOutputTokens)) + 256 > contextTokens))
            throw new IllegalArgumentException("explicit analysis model, tokenizer and sufficient context capacity required");
        if (!tokenEncoding.isBlank()) EncodingType.valueOf(tokenEncoding);
    }
}
