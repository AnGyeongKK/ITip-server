package com.gkk.itip.worker.config;

import com.gkk.itip.ai.OpenAiClients;
import com.openai.client.OpenAIClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisGenerationProperties.class)
public class AnalysisModelConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "itip.worker.analysis-model", name = "enabled", havingValue = "true")
    OpenAIClient analysisOpenAiClient(@Value("${itip.worker.analysis-model.api-key:}") String key,
            @Value("${itip.worker.analysis-model.base-url:https://api.openai.com/v1}") String baseUrl) {
        return OpenAiClients.create(key, baseUrl, Duration.ofSeconds(120), Duration.ofSeconds(120));
    }

    @Bean
    @ConditionalOnProperty(prefix = "itip.worker.analysis-model", name = "enabled", havingValue = "true")
    OpenAiChatModel analysisChatModel(@Qualifier("analysisOpenAiClient") OpenAIClient client, AnalysisGenerationProperties properties) {
        return OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder().model(properties.model()).reasoningEffort("low").maxCompletionTokens(properties.detailOutputTokens()).maxRetries(0).store(false).build()).build();
    }
}
