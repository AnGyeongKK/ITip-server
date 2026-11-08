package com.gkk.itip.chat.infrastructure;

import com.gkk.itip.chat.stream.GenerationTicket;
import com.gkk.itip.chat.stream.ChatLimits;
import com.gkk.itip.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;
import static com.gkk.itip.common.exception.ErrorCode.CHAT_AI_NOT_CONFIGURED;

@Component
public class PythonChatClient {
    private final WebClient client;
    private final String baseUrl;
    public PythonChatClient(@Value("${itip.chat.ai-base-url:}") String baseUrl,
                            @Value("${itip.chat.ai-token:}") String token) {
        this.baseUrl=baseUrl.strip();
        var builder=WebClient.builder().clientConnector(new ReactorClientHttpConnector(
            HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS,3000)))
            .codecs(c->c.defaultCodecs().maxInMemorySize(65536));
        if(!this.baseUrl.isEmpty()) {
            var uri=URI.create(this.baseUrl);
            if(uri.getHost()==null || !(uri.getScheme().equals("http")||uri.getScheme().equals("https")) || uri.getUserInfo()!=null)
                throw new IllegalArgumentException("invalid chat AI base URL");
            builder.baseUrl(this.baseUrl);
        }
        if(!token.isBlank()) builder.defaultHeader("Authorization","Bearer "+token);
        client=builder.build();
    }
    public void requireConfigured() { if(baseUrl.isEmpty()) throw new BusinessException(CHAT_AI_NOT_CONFIGURED); }
    public Flux<ServerSentEvent<JsonNode>> stream(GenerationTicket ticket, String question) {
        requireConfigured();
        return client.post().uri("/v1/chat/stream").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(Map.of("schemaVersion","1.0","requestId",ticket.clientRequestId(),"conversationId",ticket.conversationId(),
                "ownerId",ticket.ownerId(),"question",question,"records",ticket.records()))
            .retrieve().bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<JsonNode>>() {})
            .timeout(ChatLimits.IDLE_TIMEOUT).take(ChatLimits.STREAM_DURATION);
    }
}
