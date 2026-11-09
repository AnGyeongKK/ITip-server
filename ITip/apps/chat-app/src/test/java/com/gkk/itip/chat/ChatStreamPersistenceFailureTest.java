package com.gkk.itip.chat;

import com.gkk.itip.chat.conversation.service.MessageQueryService;
import com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef;
import com.gkk.itip.chat.generation.ChatAnswerGenerator;
import org.springframework.ai.chat.prompt.Prompt;
import com.gkk.itip.chat.stream.*;
import com.gkk.itip.chat.retrieval.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatStreamPersistenceFailureTest {
    @Test void storageOutageClosesStreamWithoutInventingPersistedTerminalState() {
        var generator=mock(ChatAnswerGenerator.class);var store=mock(ChatGenerationStore.class);
        var ticket=new GenerationTicket("demo-user",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),List.of(new RecordRef(UUID.randomUUID(),UUID.randomUUID())),List.of());
        when(store.refresh(ticket)).thenReturn(ticket);
        var prepared=new ChatAnswerGenerator.Prepared(new Prompt("fixture"),List.of());
        when(generator.prepare(eq("질문"),anyList(),anyList())).thenReturn(prepared);
        when(generator.stream(prepared)).thenReturn(Flux.error(new IOException("upstream disconnected")));
        when(store.fail(eq(ticket),anyString(),anyString(),anyBoolean()))
                .thenThrow(new DataAccessResourceFailureException("test database unavailable"));
        var bridge=new ChatStreamBridge(generator,store,mock(MessageQueryService.class),JsonMapper.builder().build(),
                mock(QuestionEmbeddingClient.class),mock(ChatContextQuery.class));
        var out=new ByteArrayOutputStream();
        assertThatCode(()->bridge.write(ticket,"질문",out)).doesNotThrowAnyException();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("event: meta")
                .doesNotContain("event: done","event: error","INTERNAL_ERROR","test database unavailable");
    }
}
