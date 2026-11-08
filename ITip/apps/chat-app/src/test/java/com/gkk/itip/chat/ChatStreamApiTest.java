package com.gkk.itip.chat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.gkk.itip.chat.stream.*;
import com.gkk.itip.domain.chat.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","itip.demo.owner-id=demo-user"})
@AutoConfigureMockMvc @Testcontainers
class ChatStreamApiTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    static final AtomicReference<String> RESPONSE=new AtomicReference<>();
    static final AtomicReference<String> INPUT=new AtomicReference<>();
    static final AtomicInteger CALLS=new AtomicInteger();
    static final AtomicReference<CountDownLatch> HOLD=new AtomicReference<>();
    static final AtomicLong PAUSE=new AtomicLong();
    static HttpServer python;
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) throws Exception {
        python=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        python.createContext("/v1/chat/stream", e->{
            CALLS.incrementAndGet(); INPUT.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            e.getResponseHeaders().add("Content-Type","text/event-stream"); e.sendResponseHeaders(200,0);
            String response=RESPONSE.get();
            try {
                var hold=HOLD.get(); if(hold!=null)hold.await(5,TimeUnit.SECONDS);
                if(PAUSE.get()>0)Thread.sleep(PAUSE.get());
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            try(var out=e.getResponseBody()) {
                for(byte b:response.getBytes(StandardCharsets.UTF_8)){out.write(b); out.flush();}
            } catch(java.io.IOException disconnected) { }
        });
        python.start();
        r.add("itip.chat.ai-base-url",()->"http://127.0.0.1:"+python.getAddress().getPort());
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @AfterAll static void stop(){if(python!=null)python.stop(0);}
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper mapper;
    @Autowired ChatGenerationStore store; @Autowired ChatStreamBridge bridge; @Autowired ChatMessageRepository messages;
    ChatTestData f; UUID room; ChatTestData.Analysis a; UUID chunk;
    @BeforeEach void clear(){
        f=new ChatTestData(jdbc);f.clear();a=f.analysis("demo-user","근거 영상");room=f.room("demo-user");chunk=UUID.randomUUID();
        jdbc.update("""
            insert into analysis_chunks(id,owner_id,analysis_id,section_ids,source_segment_ids,text,start_ms,end_ms,source_type,
            embedding,embedding_model,embedding_dimensions)
            values (?,'demo-user',?,array[?]::uuid[],array['segment-1'],'원문',0,1000,'CAPTION','[0.1,0.2]','fixture',2)
            """,chunk,a.analysisId(),a.sectionId());
        RESPONSE.set(event("delta","{\"text\":\"한국어 답변\"}")+event("sources","{\"items\":[]}")+event("done","{\"finishReason\":\"INSUFFICIENT_EVIDENCE\"}"));
        CALLS.set(0);INPUT.set(null);HOLD.set(null);PAUSE.set(0);
    }
    static String event(String type,String json){return "event: "+type+"\ndata: "+json+"\n\n";}
    String path(){return "/api/v1/conversations/"+room+"/messages";}
    String body(UUID key){return "{\"clientRequestId\":\""+key+"\",\"content\":\"설명해 줘\"}";}
    String send(UUID key) throws Exception {
        return send(key,10000);
    }
    String send(UUID key,long waitMs) throws Exception {
        var initial=mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content(body(key))).andExpect(request().asyncStarted()).andReturn();
        initial.getAsyncResult(waitMs);
        return mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
    @Test void streamsSplitUtf8AndStoresBeforeDoneWithoutApiResponseWrapper() throws Exception {
        UUID key=UUID.randomUUID();String text=send(key);
        assertThat(text).contains("event: meta","event: delta","한국어 답변","event: sources","event: done","INSUFFICIENT_EVIDENCE");
        assertThat(text).doesNotContain("\"code\":\"OK\"");
        var input=mapper.readTree(INPUT.get());assertThat(input.path("ownerId").asString()).isEqualTo("demo-user");
        assertThat(input.path("records").size()).isEqualTo(1);
        assertThat(input.path("records").get(0).path("recordId").asString()).isEqualTo(a.recordId().toString());
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].content").value("한국어 답변"))
                .andExpect(jsonPath("data.items[1].status").value("COMPLETED"))
                .andExpect(jsonPath("data.items[1].usedRecordCount").value(0));
        mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(key)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DUPLICATE_CHAT_REQUEST"));
        assertThat(CALLS.get()).isEqualTo(1);
    }
    @Test void validatesCitationAndDerivesMetadataFromDatabase() throws Exception {
        RESPONSE.set(event("delta","{\"text\":\"근거 답변\"}")+event("sources","{\"items\":[{\"recordId\":\""+a.recordId()+
                "\",\"chunkId\":\""+chunk+"\",\"sectionId\":\""+a.sectionId()+"\"}]}")+event("done","{\"finishReason\":\"STOP\"}"));
        UUID key=UUID.randomUUID();String text=send(key);
        assertThat(text).contains("근거 영상","src-1","\"usedRecordCount\":1");
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].sources[0].videoId").value(a.videoId()))
                .andExpect(jsonPath("data.items[1].sources[0].startMs").value(0))
                .andExpect(jsonPath("data.items[1].usedRecordCount").value(1));
    }
    @Test void eofIsIncompleteAndPreservesPartialText() throws Exception {
        RESPONSE.set(event("delta","{\"text\":\"끊긴 답변\"}"));UUID key=UUID.randomUUID();
        assertThat(send(key)).contains("event: error","INCOMPLETE").doesNotContain("event: done");
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].status").value("INCOMPLETE"))
                .andExpect(jsonPath("data.items[1].content").value("끊긴 답변"));
    }
    @Test void rejectsForeignOrUnknownCitationsWithoutPublishingSources() throws Exception {
        RESPONSE.set(event("delta","{\"text\":\"답변\"}")+event("sources","{\"items\":[{\"recordId\":\""+UUID.randomUUID()+
                "\",\"chunkId\":\""+chunk+"\",\"sectionId\":\""+a.sectionId()+"\"}]}")+event("done","{\"finishReason\":\"STOP\"}"));
        assertThat(send(UUID.randomUUID())).contains("event: error").doesNotContain("event: sources","event: done");
        assertThat(jdbc.queryForObject("select count(*) from chat_message_sources",Integer.class)).isZero();
    }
    @Test void earlyInvalidInputStaysJsonAndNeverCallsPython() throws Exception {
        UUID key=UUID.randomUUID();
        mvc.perform(post(path()).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).content(body(key)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"clientRequestId\":\""+key+"\",\"content\":\"   \"}"))
                .andExpect(status().is(422)).andExpect(jsonPath("code").value("INVALID_CHAT_MESSAGE"));
        assertThat(CALLS.get()).isZero();assertThat(jdbc.queryForObject("select count(*) from chat_messages",Integer.class)).isZero();
    }
    @Test void malformedJsonIsNotMarkedAsRetryableTransportFailure() throws Exception {
        RESPONSE.set("event: delta\ndata: {broken}\n\n"); UUID key=UUID.randomUUID();
        assertThat(send(key)).contains("CHAT_INVALID_AI_RESPONSE","event: error").doesNotContain("event: done");
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].status").value("FAILED"))
                .andExpect(jsonPath("data.items[1].retryable").value(false));
    }
    @Test void forwardsCommentHeartbeatWithoutTreatingItAsAnAnswerOrCompletion() throws Exception {
        RESPONSE.set(": heartbeat\n\n"+RESPONSE.get());
        UUID key=UUID.randomUUID();String text=send(key);
        assertThat(text).contains(": heartbeat\n\n","event: delta","event: done").doesNotContain("event: error");
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].content").value("한국어 답변"));
    }
    @Test void passesCompletedQuestionAnswerContextForFollowUp() throws Exception {
        send(UUID.randomUUID());send(UUID.randomUUID());
        var history=mapper.readTree(INPUT.get()).path("history");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("role").asString()).isEqualTo("USER");
        assertThat(history.get(0).path("content").asString()).isEqualTo("설명해 줘");
        assertThat(history.get(1).path("role").asString()).isEqualTo("ASSISTANT");
        assertThat(history.get(1).path("content").asString()).isEqualTo("한국어 답변");
    }
    @Test void doesNotSendDeletedSourcesOldAnswerAsFollowUpContext() throws Exception {
        RESPONSE.set(event("delta","{\"text\":\"삭제될 기록의 답변\"}")+event("sources","{\"items\":[{\"recordId\":\""+a.recordId()+
                "\",\"chunkId\":\""+chunk+"\",\"sectionId\":\""+a.sectionId()+"\"}]}")+event("done","{\"finishReason\":\"STOP\"}"));
        UUID old=UUID.randomUUID();send(old);
        jdbc.update("delete from analysis_records where id=?",a.recordId());f.analysis("demo-user","새 기록");
        RESPONSE.set(event("delta","{\"text\":\"새 안내\"}")+event("sources","{\"items\":[]}")+event("done","{\"finishReason\":\"INSUFFICIENT_EVIDENCE\"}"));
        send(UUID.randomUUID());assertThat(mapper.readTree(INPUT.get()).path("history").size()).isZero();
        mvc.perform(get(path()).param("clientRequestId",old.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].content").value("삭제될 기록의 답변"));
    }
    @Test void contextNeverCrossesConversationAndSkipsOversizedPairs() throws Exception {
        send(UUID.randomUUID());room=f.room("demo-user");
        UUID key=UUID.randomUUID();UUID question=f.question(room,key,1,"이전 질문");
        UUID answer=f.answer(room,key,question,2,"COMPLETED","긴".repeat(32001),1);
        jdbc.update("update chat_messages set finish_reason='INSUFFICIENT_EVIDENCE' where id=?",answer);
        send(UUID.randomUUID());
        assertThat(mapper.readTree(INPUT.get()).path("history").size()).isZero();
    }
    @Test void activeStreamBlocksSecondQuestionAndDeletionButAllowsReads() throws Exception {
        var gate=new CountDownLatch(1);HOLD.set(gate);UUID key=UUID.randomUUID();
        var initial=mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(body(key))).andExpect(request().asyncStarted()).andReturn();
        try {
            mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                    .andExpect(jsonPath("data.items[1].status").value("GENERATING"));
            UUID other=UUID.randomUUID();
            mvc.perform(post(path()).header("Idempotency-Key",other).contentType(MediaType.APPLICATION_JSON).content(body(other)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("code").value("CHAT_GENERATION_IN_PROGRESS"));
            mvc.perform(delete("/api/v1/conversations/"+room)).andExpect(status().isConflict());
        } finally {gate.countDown();}
        initial.getAsyncResult(10000);mvc.perform(asyncDispatch(initial)).andExpect(status().isOk());
        assertThat(CALLS.get()).isEqualTo(1);
    }
    @Test void deletingRecordDuringGenerationPreventsFinalCitationPublication() throws Exception {
        RESPONSE.set(event("delta","{\"text\":\"답변\"}")+event("sources","{\"items\":[{\"recordId\":\""+a.recordId()+
                "\",\"chunkId\":\""+chunk+"\",\"sectionId\":\""+a.sectionId()+"\"}]}")+event("done","{\"finishReason\":\"STOP\"}"));
        var gate=new CountDownLatch(1);HOLD.set(gate);UUID key=UUID.randomUUID();
        var initial=mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(body(key))).andExpect(request().asyncStarted()).andReturn();
        try {jdbc.update("delete from analysis_records where id=?",a.recordId());}finally{gate.countDown();}
        initial.getAsyncResult(10000);String text=mvc.perform(asyncDispatch(initial)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).contains("event: error").doesNotContain("event: sources","event: done");
        assertThat(jdbc.queryForObject("select count(*) from chat_message_sources",Integer.class)).isZero();
    }
    @Test void clientWriteFailureKeepsPartialAnswerAndClosesStream() {
        UUID key=UUID.randomUUID();var ticket=store.begin("demo-user",room,key,new SendMessageRequest(key,"질문",null));
        bridge.write(ticket,"질문",rejectEvent("delta"));
        var answer=messages.findById(ticket.assistantMessageId()).orElseThrow();
        assertThat(answer.getStatus()).isEqualTo(MessageStatus.INCOMPLETE);
        assertThat(answer.getContent()).isEqualTo("한국어 답변");assertThat(answer.getGenerationToken()).isNull();
    }
    @Test void doneWriteFailureDoesNotUndoCommittedAnswer() {
        UUID key=UUID.randomUUID();var ticket=store.begin("demo-user",room,key,new SendMessageRequest(key,"질문",null));
        bridge.write(ticket,"질문",rejectEvent("done"));
        var answer=messages.findById(ticket.assistantMessageId()).orElseThrow();
        assertThat(answer.getStatus()).isEqualTo(MessageStatus.COMPLETED);
        assertThat(answer.getFinishReason()).isEqualTo(FinishReason.INSUFFICIENT_EVIDENCE);
    }
    @Test void upstreamIdleTimeoutPersistsFailureAndReleasesRoom() throws Exception {
        PAUSE.set(31000);UUID key=UUID.randomUUID();
        assertThat(send(key,35000)).contains("event: error","CHAT_STREAM_FAILED").doesNotContain("event: done");
        var pair=messages.findByConversationIdAndClientRequestIdOrderByPositionAsc(room,key);
        assertThat(pair.getLast().getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(pair.getLast().getGenerationToken()).isNull();
        UUID next=UUID.randomUUID();
        assertThat(store.begin("demo-user",room,next,new SendMessageRequest(next,"다시 질문",null))).isNotNull();
    }
    private OutputStream rejectEvent(String event) {
        return new OutputStream(){
            @Override public void write(int value) { }
            @Override public void write(byte[] bytes,int offset,int length) throws IOException {
                if(new String(bytes,offset,length,StandardCharsets.UTF_8).startsWith("event: "+event+"\n"))
                    throw new IOException("simulated closed client");
            }
        };
    }
}
