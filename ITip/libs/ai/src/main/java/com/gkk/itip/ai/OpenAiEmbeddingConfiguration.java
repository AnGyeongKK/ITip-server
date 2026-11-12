package com.gkk.itip.ai;

import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;
import com.openai.client.OpenAIClient;
import org.springframework.beans.factory.annotation.Qualifier;

/** Worker·Chat이 같은 실제 공급자 옵션을 사용한다. API 앱은 이 모듈에 의존하지 않는다. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="itip.ai.embedding",name="enabled",havingValue="true")
public class OpenAiEmbeddingConfiguration {
    @Bean(destroyMethod="close")
    OpenAIClient embeddingOpenAiClient(@Value("${itip.ai.embedding.api-key:}") String key,
            @Value("${itip.ai.embedding.base-url:https://api.openai.com/v1}") String baseUrl) {
        return OpenAiClients.create(key,baseUrl,Duration.ofSeconds(8),Duration.ofSeconds(8));
    }
    @Bean
    OpenAiEmbeddingModel embeddingModel(@Qualifier("embeddingOpenAiClient") OpenAIClient client,
            @Value("${itip.ai.embedding.model:}") String model,
            @Value("${itip.ai.embedding.dimensions:0}") int dimensions) {
        if(model.isBlank() || dimensions<1 || dimensions>16000)
            throw new IllegalStateException("OPENAI_API_KEY, EMBEDDING_MODEL and EMBEDDING_DIMENSIONS are required");
        return OpenAiEmbeddingModel.builder().openAiClient(client).options(OpenAiEmbeddingOptions.builder()
            .model(model).dimensions(dimensions).maxRetries(0).build()).build();
    }
}
