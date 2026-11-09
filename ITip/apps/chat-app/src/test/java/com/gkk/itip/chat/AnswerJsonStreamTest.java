package com.gkk.itip.chat;

import com.gkk.itip.chat.generation.*;
import com.gkk.itip.chat.retrieval.ChatContext;
import com.gkk.itip.chat.stream.ChatProtocolException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.io.EOFException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnswerJsonStreamTest {
    static ChatResponse response(String text,String finish) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
            ChatGenerationMetadata.builder().finishReason(finish).build())));
    }
    static ChatAnswerGenerator.Prepared prepared() {
        return new ChatAnswerGenerator.Prepared(new Prompt("unused"),List.of(
            new ChatContext(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),List.of(UUID.randomUUID()),"video","제목","원문",0,1000)));
    }
    @Test void streamsOnlyDecodedAnswerAcrossEveryPossibleSingleCharacterBoundary() throws Exception {
        var stream=new AnswerJsonStream();var deltas=new StringBuilder();
        String raw="{\"answer\":\"한글\\n\\\"{}\\\"\\uD83D\\uDE00\",\"citations\":[],\"evidenceStatus\":\"INSUFFICIENT_EVIDENCE\"}";
        for(char c:raw.toCharArray()) deltas.append(stream.accept(response(String.valueOf(c),null)));
        stream.accept(response("","STOP"));var result=stream.finish(prepared());
        assertThat(deltas.toString()).isEqualTo("한글\n\"{}\"😀");
        assertThat(result.answer()).isEqualTo(deltas.toString());assertThat(result.citations()).isEmpty();
        assertThat(result.reason().name()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
    @Test void mapsModelContextLabelsToActualRetrievedIds() throws Exception {
        var p=prepared();var context=p.contexts().getFirst();var stream=new AnswerJsonStream();
        stream.accept(response("{\"answer\":\"근거 답변\",\"citations\":[{\"contextId\":\"C1\",\"sectionId\":\""+
            context.sectionIds().getFirst()+"\"}],\"evidenceStatus\":\"SUPPORTED\"}","STOP"));
        var result=stream.finish(p);
        assertThat(result.citations()).hasSize(1);assertThat(result.citations().getFirst().chunkId()).isEqualTo(context.chunkId());
        assertThat(result.citations().getFirst().recordId()).isEqualTo(context.recordId());
    }
    @Test void rejectsUnsentContextOrForeignSectionEvenWithNormalProviderStop() {
        for(String item:List.of("{\"contextId\":\"C2\",\"sectionId\":\""+UUID.randomUUID()+"\"}",
                "{\"contextId\":\"C1\",\"sectionId\":\""+UUID.randomUUID()+"\"}")) {
            var stream=new AnswerJsonStream();stream.accept(response("{\"answer\":\"본문\",\"citations\":["+item+"],\"evidenceStatus\":\"SUPPORTED\"}","STOP"));
            assertThatThrownBy(()->stream.finish(prepared())).isInstanceOf(ChatProtocolException.class);
        }
    }
    @Test void requiresRealProviderTerminationAndRejectsLengthLimitOrRefusal() {
        String json="{\"answer\":\"STOP도 본문 데이터\",\"citations\":[],\"evidenceStatus\":\"INSUFFICIENT_EVIDENCE\"}";
        var noTerminal=new AnswerJsonStream();noTerminal.accept(response(json,null));
        assertThatThrownBy(()->noTerminal.finish(prepared())).isInstanceOf(EOFException.class);
        for(String terminal:List.of("LENGTH","CONTENT_FILTER")) {
            var stream=new AnswerJsonStream();stream.accept(response(json,terminal));
            assertThatThrownBy(()->stream.finish(prepared())).isInstanceOf(ChatAnswerException.class);
        }
        var refusal=new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .properties(Map.of("refusal","private refusal text")).build())));
        assertThatThrownBy(()->new AnswerJsonStream().accept(refusal)).isInstanceOf(ChatAnswerException.class);
    }
    @Test void rejectsTruncatedDuplicateAndTrailingStructuredResults() {
        for(String json:List.of("{\"answer\":\"잘린 본문", "{\"answer\":\"A\",\"citations\":[", "{\"answer\":\"A\",\"answer\":\"B\",\"citations\":[],\"evidenceStatus\":\"INSUFFICIENT_EVIDENCE\"}",
                "{\"answer\":\"A\",\"citations\":[],\"evidenceStatus\":\"INSUFFICIENT_EVIDENCE\"} {}")) {
            var stream=new AnswerJsonStream();stream.accept(response(json,"STOP"));
            assertThatThrownBy(()->stream.finish(prepared())).isInstanceOf(ChatProtocolException.class);
        }
    }
}
