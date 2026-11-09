package com.gkk.itip.chat.config;

import com.gkk.itip.chat.generation.ChatAnswerGenerator;
import com.gkk.itip.chat.stream.ChatLimits;
import com.knuddels.jtokkit.api.EncodingType;
import com.gkk.itip.ai.OpenAiClients;
import com.openai.client.OpenAIClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="itip.chat.generation",name="enabled",havingValue="true")
public class ChatModelConfiguration {
    @Bean(destroyMethod="close")
    OpenAIClient chatOpenAiClient(@Value("${itip.chat.generation.api-key:}") String key,
            @Value("${itip.chat.generation.base-url:https://api.openai.com/v1}") String baseUrl) {
        return OpenAiClients.create(key,baseUrl,ChatLimits.STREAM_DURATION,ChatLimits.IDLE_TIMEOUT);
    }
    @Bean
    OpenAiChatModel answerModel(@Qualifier("chatOpenAiClient") OpenAIClient client,
            @Value("${itip.chat.generation.model:}") String model,
            @Value("${itip.chat.generation.token-encoding:}") String encoding,
            @Value("${itip.chat.generation.context-tokens:0}") int context,
            @Value("${itip.chat.generation.input-token-budget:8000}") int input,
            @Value("${itip.chat.generation.max-output-tokens:2048}") int output) {
        if(model.isBlank() || encoding.isBlank() || input<1 || output<1 || (long)input+output+256>context)
            throw new IllegalStateException("Chat API key, model, tokenizer and sufficient context token capacity are required");
        EncodingType.valueOf(encoding);
        return OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async())
            .options(OpenAiChatOptions.builder().model(model).maxRetries(0).maxCompletionTokens(output)
            .responseFormat(OpenAiChatModel.ResponseFormat.builder().jsonSchema(ChatAnswerGenerator.SCHEMA).strict(true).build())
            .streamUsage(true).build()).build();
    }
}
