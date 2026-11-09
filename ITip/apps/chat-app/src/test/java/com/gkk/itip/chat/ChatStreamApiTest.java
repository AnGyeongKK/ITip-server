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

@SpringBootTest(properties={"spring.flyway.enabled=true","itip.demo.owner-id=demo-user","itip.chat.retrieval-top-k=2"})
@AutoConfigureMockMvc @Testcontainers
@org.springframework.context.annotation.Import(ChatEmbeddingFixture.class)
class ChatStreamApiTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    static final AtomicReference<String> RESPONSE=new AtomicReference<>();
    static final AtomicReference<String> INPUT=new AtomicReference<>();
    static final AtomicInteger CALLS=new AtomicInteger();
    static final AtomicReference<CountDownLatch> HOLD=new AtomicReference<>();
    static final AtomicLong PAUSE=new AtomicLong();
    static HttpServer model;
    static final AtomicInteger HTTP_STATUS=new AtomicInteger(200);
    static final AtomicReference<CountDownLatch> RECEIVED=new AtomicReference<>();
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) throws Exception {
        model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/v1/chat/completions", e->{
            CALLS.incrementAndGet(); INPUT.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            RECEIVED.get().countDown();
            e.getResponseHeaders().add("Content-Type",HTTP_STATUS.get()==200?"text/event-stream":"application/json"); e.sendResponseHeaders(HTTP_STATUS.get(),0);
            String response=RESPONSE.get();
            try {
                var hold=HOLD.get(); if(hold!=null)hold.await(5,TimeUnit.SECONDS);
                if(PAUSE.get()>0)Thread.sleep(PAUSE.get());
            } catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            try(var out=e.getResponseBody()) {
                for(byte b:response.getBytes(StandardCharsets.UTF_8)){out.write(b); out.flush();}
            } catch(java.io.IOException disconnected) { }
        });
        model.start();
        ChatModelFixture.properties(r,model.getAddress().getPort());
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @AfterAll static void stop(){if(model!=null)model.stop(0);}
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper mapper;
    @Autowired ChatGenerationStore store; @Autowired ChatStreamBridge bridge; @Autowired ChatMessageRepository messages;
    @Autowired ChatEmbeddingFixture.FixtureModel embedding;
    @Autowired com.gkk.itip.chat.retrieval.ChatContextQuery retrieval;
    ChatTestData f; UUID room; ChatTestData.Analysis a; UUID chunk;
    @BeforeEach void clear(){
        f=new ChatTestData(jdbc);f.clear();a=f.analysis("demo-user","근거 영상");room=f.room("demo-user");chunk=UUID.randomUUID();
        jdbc.update("""
            insert into analysis_chunks(id,owner_id,analysis_id,section_ids,source_segment_ids,text,start_ms,end_ms,source_type,
            embedding,embedding_model,embedding_dimensions)
            values (?,'demo-user',?,array[?]::uuid[],array['segment-1'],'원문',0,1000,'CAPTION','[0.1,0.2]','fixture',2)
            """,chunk,a.analysisId(),a.sectionId());
        RESPONSE.set(ChatModelFixture.answer("한국어 답변",null,null));
        CALLS.set(0);INPUT.set(null);HOLD.set(null);PAUSE.set(0);HTTP_STATUS.set(200);RECEIVED.set(new CountDownLatch(1));
        embedding.response=request->ChatEmbeddingFixture.vector("fixture",1,0);
    }
    tools.jackson.databind.JsonNode modelInput() {
        return mapper.readTree(mapper.readTree(INPUT.get()).path("messages").get(1).path("content").asString());
    }
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
        var input=mapper.readTree(INPUT.get());
        assertThat(input.has("messages")).isTrue();assertThat(input.has("records")).isFalse();
        var data=modelInput();assertThat(data.path("contexts").size()).isEqualTo(1);
        assertThat(data.path("contexts").get(0).path("text").asString()).isEqualTo("원문");
        assertThat(data.path("contexts").get(0).path("contextId").asString()).isEqualTo("C1");
        assertThat(data.path("contexts").get(0).path("sectionIds").get(0).asString()).isEqualTo(a.sectionId().toString());
        assertThat(text).doesNotContain("\"evidenceStatus\":","\"citations\":");
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].content").value("한국어 답변"))
                .andExpect(jsonPath("data.items[1].status").value("COMPLETED"))
                .andExpect(jsonPath("data.items[1].usedRecordCount").value(0));
        mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(key)))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("DUPLICATE_CHAT_REQUEST"));
        assertThat(CALLS.get()).isEqualTo(1);
    }
    @Test void validatesCitationAndDerivesMetadataFromDatabase() throws Exception {
        RESPONSE.set(ChatModelFixture.answer("근거 답변","C1",a.sectionId()));
        UUID key=UUID.randomUUID();String text=send(key);
        assertThat(text).contains("근거 영상","src-1","\"usedRecordCount\":1");
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].sources[0].videoId").value(a.videoId()))
                .andExpect(jsonPath("data.items[1].sources[0].startMs").value(0))
                .andExpect(jsonPath("data.items[1].usedRecordCount").value(1));
    }
    @Test void retrievalFiltersBeforeTopKAndOnlyComparesMatchingModelsAndDimensions() {
        var second=f.analysis("demo-user","두 번째 허용 기록");
        UUID secondChunk=addChunk(second,"demo-user","[0.2,0.8]","fixture",2);
        var foreign=f.analysis("other-user","타인 원문");
        for(int n=0;n<5;n++) addChunk(foreign,"other-user","[1,0]","fixture",2);
        var outside=f.analysis("demo-user","범위 밖"); addChunk(outside,"demo-user","[1,0]","fixture",2);
        var deleted=f.analysis("demo-user","삭제 원문"); addChunk(deleted,"demo-user","[1,0]","fixture",2);
        jdbc.update("delete from analysis_records where id=?",deleted.recordId());
        addChunk(a,"demo-user","[1,0]","other-model",2);
        addChunk(a,"demo-user","[1,0,0]","fixture",3);
        var allowed=List.of(new com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef(a.recordId(),a.analysisId()),
                new com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef(second.recordId(),second.analysisId()),
                new com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef(foreign.recordId(),foreign.analysisId()),
                new com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef(deleted.recordId(),deleted.analysisId()));
        var found=retrieval.search("demo-user",allowed,new com.gkk.itip.chat.retrieval.QuestionEmbeddingClient.Vector("fixture",new float[]{1,0}));
        assertThat(found).extracting(com.gkk.itip.chat.retrieval.ChatContext::chunkId).containsExactly(chunk,secondChunk);
        assertThat(found.getFirst().text()).isEqualTo("원문");
        assertThat(found.getFirst().sectionIds()).containsExactly(a.sectionId());
    }
    @Test void rejectsValidCitationThatWasNotRetrievedAndSentToModel() throws Exception {
        addChunk(a,"demo-user","[1,0]","fixture",2);
        addChunk(a,"demo-user","[0,1]","fixture",2);
        RESPONSE.set(ChatModelFixture.answer("답변","C3",a.sectionId()));
        assertThat(send(UUID.randomUUID())).contains("CHAT_INVALID_AI_RESPONSE").doesNotContain("event: sources","event: done");
        assertThat(modelInput().path("contexts").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from chat_message_sources",Integer.class)).isZero();
    }
    @Test void noCompatibleEmbeddingSendsEmptyContextsForInsufficientEvidence() throws Exception {
        jdbc.update("update analysis_chunks set embedding_model='different-model'");
        assertThat(send(UUID.randomUUID())).contains("event: done","INSUFFICIENT_EVIDENCE");
        assertThat(modelInput().path("contexts").size()).isZero();
    }
    @Test void tokenBudgetExcludedContextCannotBeCitedEvenThoughSqlRetrievedIt() throws Exception {
        jdbc.update("update analysis_chunks set embedding='[1,0]' where id=?",chunk);
        UUID pruned=addChunk(a,"demo-user","[0.1,0.2]","fixture",2);
        jdbc.update("update analysis_chunks set text=? where id=?","x7Q9!z2V8#".repeat(1800),pruned);
        RESPONSE.set(ChatModelFixture.answer("답변","C2",a.sectionId()));
        assertThat(send(UUID.randomUUID())).contains("CHAT_INVALID_AI_RESPONSE").doesNotContain("event: sources","event: done");
        assertThat(modelInput().path("contexts").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analysis_chunks where analysis_id=?",Integer.class,a.analysisId())).isEqualTo(2);
    }
    @Test void rejectsCompleteJsonWithoutProviderStopAndTruncatedCitations() throws Exception {
        for(String json:List.of("{\"answer\":\"부분 답변\",\"citations\":[",
                "{\"answer\":\"부분 답변\",\"citations\":[],\"evidenceStatus\":\"INSUFFICIENT_EVIDENCE\"}")) {
            RESPONSE.set(ChatModelFixture.raw(json,json.endsWith("[")?"stop":null));
            UUID key=UUID.randomUUID();
            assertThat(send(key)).contains("INCOMPLETE","event: error").doesNotContain("event: sources","event: done");
            assertThat(messages.findByConversationIdAndClientRequestIdOrderByPositionAsc(room,key).getLast().getContent()).isEqualTo("부분 답변");
        }
    }
    @Test void sdkHttpErrorsAreSanitizedAndAttemptedOnlyOnce() throws Exception {
        for(int http:new int[]{401,408,429,503}) {
            HTTP_STATUS.set(http);CALLS.set(0);
            RESPONSE.set("{\"error\":{\"message\":\"private provider detail\",\"type\":\"fixture_error\"}}");
            UUID key=UUID.randomUUID();String result=send(key);
            assertThat(result).contains("event: error").doesNotContain("private provider detail","event: done");
            assertThat(CALLS.get()).isEqualTo(1);
            assertThat(messages.findByConversationIdAndClientRequestIdOrderByPositionAsc(room,key).getLast().getRetryable()).isEqualTo(http!=401);
        }
    }
    @Test void providerRefusalDoesNotBecomeACompletedAnswer() throws Exception {
        RESPONSE.set("data: {\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture-answer\",\"choices\":[{\"index\":0,\"delta\":{\"refusal\":\"private refusal\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        assertThat(send(UUID.randomUUID())).contains("CHAT_AI_REFUSED","FAILED").doesNotContain("private refusal","event: done","event: sources");
    }
    @Test void embeddingFailurePersistsSafeFailureAndNeverSendsRawQuestionToModel() throws Exception {
        embedding.response=request->{throw new IllegalStateException("provider credential should not leak");};
        UUID key=UUID.randomUUID();
        assertThat(send(key)).contains("CHAT_RETRIEVAL_FAILED","event: error").doesNotContain("event: done","credential");
        assertThat(CALLS.get()).isZero();
        assertThat(messages.findByConversationIdAndClientRequestIdOrderByPositionAsc(room,key).getLast().getStatus()).isEqualTo(MessageStatus.FAILED);
    }
    @Test void rechecksDeletedRecordsAfterEmbeddingBeforeBuildingModelContext() throws Exception {
        embedding.response=request->{jdbc.update("delete from analysis_records where id=?",a.recordId());return ChatEmbeddingFixture.vector("fixture",1,0);};
        assertThat(send(UUID.randomUUID())).contains("INSUFFICIENT_EVIDENCE","event: done");
        assertThat(modelInput().path("contexts").size()).isZero();
        assertThat(modelInput().path("history").size()).isZero();
    }
    private UUID addChunk(ChatTestData.Analysis analysis,String owner,String vector,String model,int dimensions) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            insert into analysis_chunks(id,owner_id,analysis_id,section_ids,source_segment_ids,text,start_ms,end_ms,source_type,
              embedding,embedding_model,embedding_dimensions)
            values (?,?,?,array[?]::uuid[],array['segment-1'],'추가 원문',0,1000,'CAPTION',?::vector,?,?)
            """,id,owner,analysis.analysisId(),analysis.sectionId(),vector,model,dimensions);
        return id;
    }
    @Test void eofIsIncompleteAndPreservesPartialText() throws Exception {
        RESPONSE.set(ChatModelFixture.raw("{\"answer\":\"끊긴 답변",null));UUID key=UUID.randomUUID();
        assertThat(send(key)).contains("event: error","INCOMPLETE").doesNotContain("event: done");
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].status").value("INCOMPLETE"))
                .andExpect(jsonPath("data.items[1].content").value("끊긴 답변"));
    }
    @Test void rejectsForeignOrUnknownCitationsWithoutPublishingSources() throws Exception {
        RESPONSE.set(ChatModelFixture.answer("답변","C999",a.sectionId()));
        assertThat(send(UUID.randomUUID())).contains("event: error").doesNotContain("event: sources","event: done");
        assertThat(jdbc.queryForObject("select count(*) from chat_message_sources",Integer.class)).isZero();
    }
    @Test void earlyInvalidInputStaysJsonAndNeverCallsModel() throws Exception {
        UUID key=UUID.randomUUID();
        mvc.perform(post(path()).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM).content(body(key)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"clientRequestId\":\""+key+"\",\"content\":\"   \"}"))
                .andExpect(status().is(422)).andExpect(jsonPath("code").value("INVALID_CHAT_MESSAGE"));
        assertThat(CALLS.get()).isZero();assertThat(jdbc.queryForObject("select count(*) from chat_messages",Integer.class)).isZero();
    }
    @Test void malformedJsonIsNotMarkedAsRetryableTransportFailure() throws Exception {
        RESPONSE.set(ChatModelFixture.raw("{broken","stop")); UUID key=UUID.randomUUID();
        assertThat(send(key)).contains("CHAT_INVALID_AI_RESPONSE","event: error").doesNotContain("event: done");
        mvc.perform(get(path()).param("clientRequestId",key.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].status").value("FAILED"))
                .andExpect(jsonPath("data.items[1].retryable").value(false));
    }
    @Test void providerLengthLimitKeepsPartialAnswerWithoutPublishingCompletion() throws Exception {
        RESPONSE.set(ChatModelFixture.raw("{\"answer\":\"길이 제한으로 남은 답변", "length"));
        UUID key=UUID.randomUUID();
        assertThat(send(key)).contains("CHAT_ANSWER_TOO_LONG","INCOMPLETE").doesNotContain("event: done","event: sources");
        assertThat(messages.findByConversationIdAndClientRequestIdOrderByPositionAsc(room,key).getLast().getContent()).isEqualTo("길이 제한으로 남은 답변");
    }
    @Test void passesCompletedQuestionAnswerContextForFollowUp() throws Exception {
        send(UUID.randomUUID());send(UUID.randomUUID());
        var history=modelInput().path("history");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("role").asString()).isEqualTo("USER");
        assertThat(history.get(0).path("content").asString()).isEqualTo("설명해 줘");
        assertThat(history.get(1).path("role").asString()).isEqualTo("ASSISTANT");
        assertThat(history.get(1).path("content").asString()).isEqualTo("한국어 답변");
    }
    @Test void doesNotSendDeletedSourcesOldAnswerAsFollowUpContext() throws Exception {
        RESPONSE.set(ChatModelFixture.answer("삭제될 기록의 답변","C1",a.sectionId()));
        UUID old=UUID.randomUUID();send(old);
        jdbc.update("delete from analysis_records where id=?",a.recordId());f.analysis("demo-user","새 기록");
        RESPONSE.set(ChatModelFixture.answer("새 안내",null,null));
        send(UUID.randomUUID());assertThat(modelInput().path("history").size()).isZero();
        mvc.perform(get(path()).param("clientRequestId",old.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[1].content").value("삭제될 기록의 답변"));
    }
    @Test void contextNeverCrossesConversationAndSkipsOversizedPairs() throws Exception {
        send(UUID.randomUUID());room=f.room("demo-user");
        UUID key=UUID.randomUUID();UUID question=f.question(room,key,1,"이전 질문");
        UUID answer=f.answer(room,key,question,2,"COMPLETED","긴".repeat(32001),1);
        jdbc.update("update chat_messages set finish_reason='INSUFFICIENT_EVIDENCE' where id=?",answer);
        send(UUID.randomUUID());
        assertThat(modelInput().path("history").size()).isZero();
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
        RESPONSE.set(ChatModelFixture.answer("답변","C1",a.sectionId()));
        var gate=new CountDownLatch(1);HOLD.set(gate);UUID key=UUID.randomUUID();
        var initial=mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .content(body(key))).andExpect(request().asyncStarted()).andReturn();
        try {assertThat(RECEIVED.get().await(5,TimeUnit.SECONDS)).isTrue();jdbc.update("delete from analysis_records where id=?",a.recordId());}finally{gate.countDown();}
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
