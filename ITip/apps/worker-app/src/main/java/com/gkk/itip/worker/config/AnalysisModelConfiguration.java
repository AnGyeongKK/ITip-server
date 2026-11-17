package com.gkk.itip.worker.config;

import com.gkk.itip.ai.OpenAiClients;
import com.openai.client.OpenAIClient;
import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "itip.worker.analysis-model", name = "enabled", havingValue = "true")
public class AnalysisModelConfiguration {
    @Bean(destroyMethod = "close")
    OpenAIClient analysisOpenAiClient(
            @Value("${itip.worker.analysis-model.api-key:}") String apiKey,
            @Value("${itip.worker.analysis-model.base-url:https://api.openai.com/v1}") String baseUrl) {
        return OpenAiClients.create(apiKey, baseUrl, Duration.ofMinutes(2), Duration.ofMinutes(2));
    }

    @Bean
    OpenAiChatModel analysisChatModel(@Qualifier("analysisOpenAiClient") OpenAIClient client,
            @Value("${itip.worker.analysis-model.model:}") String model,
            @Value("${itip.worker.analysis-model.max-output-tokens:0}") int maxOutputTokens) {
        if (model.isBlank() || maxOutputTokens < 1) {
            throw new IllegalStateException("ANALYSIS_MODEL and ANALYSIS_MAX_OUTPUT_TOKENS are required");
        }
        return OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder().model(model).maxRetries(0)
                        .maxCompletionTokens(maxOutputTokens).build())
                .build();
    }
}
