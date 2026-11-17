package com.gkk.itip.chat;

import com.gkk.itip.chat.conversation.dto.MessageView;
import com.gkk.itip.chat.conversation.service.MessageQueryService;
import com.gkk.itip.chat.generation.ChatAnswerGenerator;
import com.gkk.itip.chat.retrieval.ChatContextQuery;
import com.gkk.itip.chat.retrieval.QuestionEmbeddingClient;
import com.gkk.itip.chat.stream.ChatGenerationStore;
import com.gkk.itip.chat.stream.ChatStreamBridge;
import com.gkk.itip.chat.stream.GenerationTicket;
import com.gkk.itip.domain.chat.FinishReason;
import com.gkk.itip.domain.chat.MessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.gkk.itip.chat.AnswerJsonStreamTest.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamBridgeTest {
    @Test
    void providerBurstDoesNotOverflowBeforeTheServletCanReadTheNextChunk() {
        var generator = mock(ChatAnswerGenerator.class);
        var store = mock(ChatGenerationStore.class);
        var messages = mock(MessageQueryService.class);
        var ticket = new GenerationTicket("demo-user", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), List.of(), List.of(), null);
        var prepared = new ChatAnswerGenerator.Prepared(new Prompt("fixture"), List.of());
        var answer = mock(MessageView.Assistant.class);
        when(answer.status()).thenReturn(MessageStatus.COMPLETED);
        when(answer.finishReason()).thenReturn(FinishReason.INSUFFICIENT_EVIDENCE);
        when(answer.sources()).thenReturn(List.of());
        when(store.refresh(ticket)).thenReturn(ticket);
        when(store.checkpoint(eq(ticket), anyString())).thenReturn(true);
        when(store.complete(ticket, "첫 번째 두 번째", FinishReason.INSUFFICIENT_EVIDENCE, List.of(), List.of()))
                .thenReturn(true);
        when(messages.get(ticket.ownerId(), ticket.conversationId(), ticket.assistantMessageId())).thenReturn(answer);
        when(generator.prepare("질문", List.of(), List.of())).thenReturn(prepared);
        // Force the burst to hit the same bufferUntil operator used by the Spring AI OpenAI adapter.
        when(generator.stream(prepared)).thenReturn(Flux.<ChatResponse>create(sink -> {
            sink.next(response("{\"answer\":\"첫 번째 ", null));
            sink.next(response("두 번째", null));
            sink.next(response("\",\"citations\":[],\"evidenceStatus\":\"INSUFFICIENT_EVIDENCE\"}", "STOP"));
            sink.complete();
        }, FluxSink.OverflowStrategy.IGNORE).bufferUntil(chunk -> true).map(List::getFirst));
        var bridge = new ChatStreamBridge(generator, store, messages, JsonMapper.builder().build(),
                mock(QuestionEmbeddingClient.class), mock(ChatContextQuery.class));
        var out = new ByteArrayOutputStream();

        bridge.write(ticket, "질문", out);

        assertThat(out.toString(StandardCharsets.UTF_8))
                .containsSubsequence("event: delta", "첫 번째 ", "event: delta", "두 번째", "event: sources", "event: done")
                .doesNotContain("event: error");
        verify(store).complete(ticket, "첫 번째 두 번째", FinishReason.INSUFFICIENT_EVIDENCE, List.of(), List.of());
    }
}
