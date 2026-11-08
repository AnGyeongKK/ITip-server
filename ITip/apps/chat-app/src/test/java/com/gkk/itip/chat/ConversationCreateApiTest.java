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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "itip.demo.owner-id=demo-user"})
@AutoConfigureMockMvc @Testcontainers
class ConversationCreateApiTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    ChatTestData f;
    static final String ALL = "{\"scope\":{\"type\":\"ALL\"}}";
    @BeforeEach void clear() { f = new ChatTestData(jdbc); f.clear(); }
    ResultActions create(UUID key, String body) throws Exception {
        return mvc.perform(post("/api/v1/conversations").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void createsRoomForOwnedReadyRecordsWithoutCreatingMessages() throws Exception {
        f.analysis("demo-user", "내 영상"); f.analysis("other-user", "타인 영상");
        var response = create(UUID.randomUUID(), ALL).andExpect(status().isCreated())
                .andExpect(jsonPath("code").value("OK")).andExpect(jsonPath("data.recordCount").value(1))
                .andExpect(jsonPath("data.scope.type").value("ALL"))
                .andExpect(jsonPath("data.scope.keywordIds").isEmpty())
                .andExpect(jsonPath("data.activeAssistantMessageId").isEmpty()).andReturn();
        String id = mapper.readTree(response.getResponse().getContentAsString()).path("data").path("conversationId").asString();
        assertThat(response.getResponse().getHeader("Location")).isEqualTo("/api/v1/conversations/" + id);
        assertThat(jdbc.queryForObject("select count(*) from chat_messages", Integer.class)).isZero();
    }

    @Test void emptyScopeAndInvalidAllFiltersReturnTypedErrors() throws Exception {
        create(UUID.randomUUID(), ALL).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("EMPTY_CHAT_SCOPE"));
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"ALL\",\"q\":\"RAG\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        create(UUID.randomUUID(), "{\"scope\":null}").andExpect(status().isBadRequest());
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"UNKNOWN\"}}").andExpect(status().isBadRequest());
    }

    @Test void sameKeyReplaysOriginalRoomAndCountEvenAfterRecordDeletion() throws Exception {
        var record = f.analysis("demo-user", "RAG 영상"); UUID key = UUID.randomUUID();
        String first = create(key, ALL).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        jdbc.update("delete from analysis_records where id=?", record.recordId());
        String second = create(key, ALL).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(second)).isEqualTo(mapper.readTree(first));
        assertThat(jdbc.queryForObject("select count(*) from chat_conversations", Integer.class)).isEqualTo(1);
        create(key, "{\"scope\":{\"type\":\"FILTERED\",\"q\":\"different\"}}")
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test void concurrentRetriesCreateOneRoom() throws Exception {
        f.analysis("demo-user", "영상"); UUID key = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return create(key, ALL).andReturn(); });
            var b = pool.submit(() -> { start.await(); return create(key, ALL).andReturn(); });
            start.countDown();
            var first = a.get(20, TimeUnit.SECONDS); var second = b.get(20, TimeUnit.SECONDS);
            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(mapper.readTree(first.getResponse().getContentAsString()))
                    .isEqualTo(mapper.readTree(second.getResponse().getContentAsString()));
        }
        assertThat(jdbc.queryForObject("select count(*) from chat_conversations", Integer.class)).isEqualTo(1);
    }

    @Test void filtersKeywordAndLiteralTextWithoutSearchingJsonFieldNamesOrCaptionText() throws Exception {
        f.analysis("demo-user", "특수 %_ 영상");
        String scope = "{\"scope\":{\"type\":\"FILTERED\",\"keywordIds\":[\"33333333-3333-4333-8333-333333333333\"],\"q\":\"  %_  \"}}";
        create(UUID.randomUUID(), scope).andExpect(status().isCreated()).andExpect(jsonPath("data.recordCount").value(1))
                .andExpect(jsonPath("data.scope.q").value("%_"));
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"FILTERED\",\"q\":\"schemaVersion\"}}")
                .andExpect(status().isUnprocessableEntity());
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"FILTERED\",\"keywordIds\":[\"44444444-4444-4444-8444-444444444444\"]}}")
                .andExpect(status().isUnprocessableEntity());
    }

    @Test void corruptStoredResultFailsClosedInsteadOfBecomingEmptyOrUsableScope() throws Exception {
        f.analysis("demo-user", "영상");
        jdbc.update("update analysis_results set result_json='{broken'");
        create(UUID.randomUUID(), ALL).andExpect(status().isInternalServerError()).andExpect(jsonPath("code").value("INTERNAL_ERROR"));
    }

    @Test void folderScopeUsesOnlyOwnedLinksAndRejectsForeignFoldersAndLinks() throws Exception {
        var mine = f.analysis("demo-user", "폴더 영상"); f.analysis("demo-user", "폴더 밖 영상");
        var other = f.analysis("other-user", "타인 영상");
        UUID folder = UUID.randomUUID(), foreign = UUID.randomUUID();
        jdbc.update("insert into folders(id,owner_id,name,created_at) values (?,'demo-user','AI 공부',now()),(?,'other-user','비공개',now())", folder, foreign);
        jdbc.update("insert into folder_records(folder_id,record_id,owner_id) values (?,?,'demo-user')", folder, mine.recordId());
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"FILTERED\",\"folderId\":\"" + folder + "\"}}")
                .andExpect(status().isCreated()).andExpect(jsonPath("data.recordCount").value(1))
                .andExpect(jsonPath("data.scopeLabel").value("AI 공부"));
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"FILTERED\",\"folderId\":\"" + foreign + "\"}}")
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> jdbc.update("insert into folder_records(folder_id,record_id,owner_id) values (?,?,'demo-user')", folder, other.recordId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
