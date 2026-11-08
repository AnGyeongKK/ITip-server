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
    static HttpServer python;
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) throws Exception {
        python=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        python.createContext("/v1/chat/stream", e->{
            CALLS.incrementAndGet(); INPUT.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            e.getResponseHeaders().add("Content-Type","text/event-stream"); e.sendResponseHeaders(200,0);
            try(var out=e.getResponseBody()) {
                for(byte b:RESPONSE.get().getBytes(StandardCharsets.UTF_8)){out.write(b); out.flush();}
            } catch(java.io.IOException disconnected) { }
        });
        python.start();
        r.add("itip.chat.ai-base-url",()->"http://127.0.0.1:"+python.getAddress().getPort());
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @AfterAll static void stop(){if(python!=null)python.stop(0);}
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper mapper;
    ChatTestData f; UUID room; ChatTestData.Analysis a; UUID chunk;
    @BeforeEach void clear(){
        f=new ChatTestData(jdbc);f.clear();a=f.analysis("demo-user","근거 영상");room=f.room("demo-user");chunk=UUID.randomUUID();
        jdbc.update("""
            insert into analysis_chunks(id,owner_id,analysis_id,section_ids,source_segment_ids,text,start_ms,end_ms,source_type,
            embedding,embedding_model,embedding_dimensions)
            values (?,'demo-user',?,array[?]::uuid[],array['segment-1'],'원문',0,1000,'CAPTION','[0.1,0.2]','fixture',2)
            """,chunk,a.analysisId(),a.sectionId());
        RESPONSE.set(event("delta","{\"text\":\"한국어 답변\"}")+event("sources","{\"items\":[]}")+event("done","{\"finishReason\":\"INSUFFICIENT_EVIDENCE\"}"));
        CALLS.set(0);INPUT.set(null);
    }
    static String event(String type,String json){return "event: "+type+"\ndata: "+json+"\n\n";}
    String path(){return "/api/v1/conversations/"+room+"/messages";}
    String body(UUID key){return "{\"clientRequestId\":\""+key+"\",\"content\":\"설명해 줘\"}";}
    String send(UUID key) throws Exception {
        var initial=mvc.perform(post(path()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).content(body(key))).andExpect(request().asyncStarted()).andReturn();
        initial.getAsyncResult(10000);
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
}
