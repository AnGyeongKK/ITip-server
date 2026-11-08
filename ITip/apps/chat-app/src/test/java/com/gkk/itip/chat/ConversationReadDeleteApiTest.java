package com.gkk.itip.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "itip.demo.owner-id=demo-user"})
@AutoConfigureMockMvc @Testcontainers
class ConversationReadDeleteApiTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper mapper;
    ChatTestData f;
    @BeforeEach void clear() { f = new ChatTestData(jdbc); f.clear(); }
    String path(UUID room) { return "/api/v1/conversations/" + room; }
    UUID createAll() throws Exception {
        var r = mvc.perform(post("/api/v1/conversations").header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":{\"type\":\"ALL\"}}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(r.getResponse().getContentAsString()).path("data").path("conversationId").asString());
    }

    @Test void listsOnlyOwnedRoomsWithStableTimestampTieCursorAndActiveAnswer() throws Exception {
        UUID low = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID mid = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID high = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
        for (UUID id : new UUID[]{low, mid, high}) jdbc.update("update chat_conversations set id=? where id=?", id, f.room("demo-user"));
        f.room("other-user");
        UUID key = UUID.randomUUID(), active = f.answer(high, key, f.question(high, key, 1, "최근 질문"), 2, "GENERATING", "", 1);
        var r = mvc.perform(get("/api/v1/conversations").param("limit", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(2))
                .andExpect(jsonPath("data.items[0].conversationId").value(high.toString()))
                .andExpect(jsonPath("data.items[0].activeAssistantMessageId").value(active.toString()))
                .andExpect(jsonPath("data.items[1].conversationId").value(mid.toString()))
                .andExpect(jsonPath("data.hasNext").value(true)).andReturn();
        String cursor = mapper.readTree(r.getResponse().getContentAsString()).path("data").path("nextCursor").asString();
        mvc.perform(get("/api/v1/conversations").param("limit", "2").param("cursor", cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].conversationId").value(low.toString()))
                .andExpect(jsonPath("data.items.length()").value(1)).andExpect(jsonPath("data.hasNext").value(false));
    }

    @Test void detailReevaluatesScopeAndKeepsRoomWhenAllRecordsAreDeleted() throws Exception {
        var original = f.analysis("demo-user", "첫 영상"); UUID room = createAll();
        mvc.perform(get(path(room))).andExpect(status().isOk()).andExpect(jsonPath("data.recordCount").value(1))
                .andExpect(jsonPath("data.scopeStatus").value("AVAILABLE"));
        var second = f.analysis("demo-user", "둘째 영상");
        mvc.perform(get(path(room))).andExpect(status().isOk()).andExpect(jsonPath("data.recordCount").value(2));
        jdbc.update("delete from analysis_records where id in (?,?)", original.recordId(), second.recordId());
        mvc.perform(get(path(room))).andExpect(status().isOk()).andExpect(jsonPath("data.recordCount").value(0))
                .andExpect(jsonPath("data.scopeStatus").value("EMPTY"));
    }

    @Test void deletedFolderIsUnavailableWithoutDeletingConversation() throws Exception {
        var record = f.analysis("demo-user", "영상"); UUID folder = UUID.randomUUID();
        jdbc.update("insert into folders(id,owner_id,name,created_at) values (?,'demo-user','공부',now())", folder);
        jdbc.update("insert into folder_records(folder_id,record_id,owner_id) values (?,?,'demo-user')", folder, record.recordId());
        UUID room = f.room("demo-user");
        jdbc.update("update chat_conversations set scope_json=? where id=?",
                "{\"type\":\"FILTERED\",\"folderId\":\"" + folder + "\",\"keywordIds\":[],\"q\":null}", room);
        jdbc.update("delete from folders where id=?", folder);
        mvc.perform(get(path(room))).andExpect(status().isOk()).andExpect(jsonPath("data.scopeStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("data.recordCount").value(0));
    }

    @Test void deletesOnlyTheRoomAndHistoryButKeepsOriginalAnalysis() throws Exception {
        var record = f.analysis("demo-user", "영상"); UUID room = createAll(), key = UUID.randomUUID();
        UUID a = f.answer(room, key, f.question(room, key, 1, "질문"), 2, "COMPLETED", "답변", 1); f.source(a, record);
        mvc.perform(delete(path(room))).andExpect(status().isOk()).andExpect(jsonPath("code").value("OK"))
                .andExpect(jsonPath("data").doesNotExist());
        mvc.perform(get(path(room))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from chat_messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from chat_message_sources", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_records", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analysis_results", Integer.class)).isEqualTo(1);
    }

    @Test void rejectsDeletionDuringGenerationAndHidesOtherOwner() throws Exception {
        UUID room = f.room("demo-user"), key = UUID.randomUUID();
        f.answer(room, key, f.question(room, key, 1, "질문"), 2, "GENERATING", "", 1);
        mvc.perform(delete(path(room))).andExpect(status().isConflict()).andExpect(jsonPath("code").value("CHAT_GENERATION_IN_PROGRESS"));
        UUID other = f.room("other-user");
        mvc.perform(get(path(other))).andExpect(status().isNotFound());
        mvc.perform(delete(path(other))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from chat_conversations", Integer.class)).isEqualTo(2);
    }

    @Test void invalidCursorAndCorruptStoredScopeFailWithoutExpandingScope() throws Exception {
        mvc.perform(get("/api/v1/conversations").param("cursor", "bad")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_CURSOR"));
        mvc.perform(get("/api/v1/conversations").param("limit", "51")).andExpect(status().isBadRequest());
        UUID room = f.room("demo-user"); jdbc.update("update chat_conversations set scope_json='{}' where id=?", room);
        mvc.perform(get(path(room))).andExpect(status().isInternalServerError()).andExpect(jsonPath("code").value("INTERNAL_ERROR"));
    }
}
