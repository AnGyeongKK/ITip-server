package com.gkk.itip.chat;

import com.gkk.itip.chat.conversation.service.MessageQueryService;
import com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef;
import com.gkk.itip.chat.infrastructure.PythonChatClient;
import com.gkk.itip.chat.stream.*;
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
        var python=mock(PythonChatClient.class);var store=mock(ChatGenerationStore.class);
        var ticket=new GenerationTicket("demo-user",UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),List.of(new RecordRef(UUID.randomUUID(),UUID.randomUUID())),List.of());
        when(python.stream(ticket,"질문")).thenReturn(Flux.error(new IOException("upstream disconnected")));
        when(store.fail(eq(ticket),anyString(),anyString(),anyBoolean()))
                .thenThrow(new DataAccessResourceFailureException("test database unavailable"));
        var bridge=new ChatStreamBridge(python,store,mock(MessageQueryService.class),JsonMapper.builder().build());
        var out=new ByteArrayOutputStream();
        assertThatCode(()->bridge.write(ticket,"질문",out)).doesNotThrowAnyException();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("event: meta")
                .doesNotContain("event: done","event: error","INTERNAL_ERROR","test database unavailable");
    }
}
