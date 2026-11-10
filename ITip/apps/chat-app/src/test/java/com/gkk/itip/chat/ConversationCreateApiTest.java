package com.gkk.itip.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "itip.demo.owner-id=demo-user"})
@AutoConfigureMockMvc @Testcontainers
@Import(ConversationCreateApiTest.NanosecondClock.class)
class ConversationCreateApiTest {
    @TestConfiguration(proxyBeanMethods = false) static class NanosecondClock {
        @Bean @Primary Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-09T00:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }
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

    @Test void addingRecordScopeFieldsPreservesExistingAllScopeIdempotencyHash() throws Exception {
        f.analysis("demo-user", "영상");
        UUID key = UUID.randomUUID();
        var created = create(key, ALL).andExpect(status().isCreated()).andReturn();
        String legacy = "{\"scope\":{\"type\":\"ALL\",\"folderId\":null,\"keywordIds\":[],\"q\":null}}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("update idempotency_requests set request_hash=? where request_key=?", hash, key);
        var replay = create(key, ALL).andExpect(status().isCreated()).andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(created.getResponse().getContentAsString());
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

    @Test void recordScopeReturnsOnlySelectedRecordAndChecksSectionMembership() throws Exception {
        var selected = f.analysis("demo-user", "선택 영상");
        var other = f.analysis("demo-user", "다른 영상");
        create(UUID.randomUUID(), recordScope(selected.recordId(), null)).andExpect(status().isCreated())
                .andExpect(jsonPath("data.recordCount").value(1))
                .andExpect(jsonPath("data.scope.recordId").value(selected.recordId().toString()))
                .andExpect(jsonPath("data.scopeLabel").value("선택 영상"));
        create(UUID.randomUUID(), recordScope(selected.recordId(), selected.sectionId())).andExpect(status().isCreated())
                .andExpect(jsonPath("data.scope.focusSectionId").value(selected.sectionId().toString()))
                .andExpect(jsonPath("data.scopeLabel").value("선택 영상 · 근거 검색"));
        create(UUID.randomUUID(), recordScope(selected.recordId(), other.sectionId())).andExpect(status().isNotFound());
        create(UUID.randomUUID(), recordScope(selected.recordId(), UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void recordScopeRejectsForeignMissingDeletedAndUnreadyRecordsWithoutFallback() throws Exception {
        f.analysis("demo-user", "대체하면 안 되는 영상");
        var foreign = f.analysis("other-user", "타인 영상");
        var unready = f.analysis("demo-user", "미완료 영상");
        var deleted = f.analysis("demo-user", "삭제 영상");
        jdbc.update("update analysis_results set chat_ready=false,completed_at=null where id=?", unready.analysisId());
        jdbc.update("delete from analysis_records where id=?", deleted.recordId());
        for (UUID id : java.util.List.of(foreign.recordId(), unready.recordId(), deleted.recordId(), UUID.randomUUID())) {
            create(UUID.randomUUID(), recordScope(id, null)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("code").value("RESOURCE_NOT_FOUND"));
        }
        assertThat(jdbc.queryForObject("select count(*) from chat_conversations", Integer.class)).isZero();
    }

    @Test void recordScopeRejectsMissingRecordAndMixedFiltersAndScopeChangesCreateNewRoom() throws Exception {
        var selected = f.analysis("demo-user", "영상");
        for (String body : java.util.List.of(
                "{\"scope\":{\"type\":\"RECORD\"}}",
                "{\"scope\":{\"type\":\"FILTERED\",\"recordId\":\"" + selected.recordId() + "\"}}",
                "{\"scope\":{\"type\":\"ALL\",\"focusSectionId\":\"" + selected.sectionId() + "\"}}",
                "{\"scope\":{\"type\":\"RECORD\",\"recordId\":\"" + selected.recordId() + "\",\"q\":\"검색\"}}")) {
            create(UUID.randomUUID(), body).andExpect(status().isBadRequest());
        }
        UUID key = UUID.randomUUID();
        String body = recordScope(selected.recordId(), null);
        var first = create(key, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(create(key, body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).isEqualTo(first);
        create(key, recordScope(selected.recordId(), selected.sectionId())).andExpect(status().isConflict());
        create(UUID.randomUUID(), recordScope(selected.recordId(), selected.sectionId())).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from chat_conversations", Integer.class)).isEqualTo(2);
    }

    private String recordScope(UUID record, UUID section) {
        return "{\"scope\":{\"type\":\"RECORD\",\"recordId\":\"" + record + "\",\"focusSectionId\":"
                + (section == null ? "null" : "\"" + section + "\"") + "}}";
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

    @Test void literalSearchPreservesInternalSpaces() throws Exception {
        f.analysis("demo-user", "큰  그림");
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"FILTERED\",\"q\":\"큰  그림\"}}")
                .andExpect(status().isCreated()).andExpect(jsonPath("data.recordCount").value(1))
                .andExpect(jsonPath("data.scope.q").value("큰  그림"));
    }

    @Test void usesTheSameSummaryKeywordAndLiteralTextFiltersAsRecordSearch() throws Exception {
        var record = f.analysis("demo-user", "vector   database");
        String sectionKeyword = "44444444-4444-4444-8444-444444444444";
        String resultJson = jdbc.queryForObject("select result_json from analysis_results where id=?",
                String.class, record.analysisId());
        var document = mapper.readTree(resultJson).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) document.path("sections").get(0)).set("keywords",
                mapper.readTree("[{\"keywordId\":\"" + sectionKeyword + "\",\"name\":\"pgvector\"}]"));
        jdbc.update("update analysis_results set result_json=? where id=?", mapper.writeValueAsString(document), record.analysisId());

        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"FILTERED\",\"keywordIds\":[\"33333333-3333-4333-8333-333333333333\"],\"q\":\"  vector   database  \"}}")
                .andExpect(status().isCreated()).andExpect(jsonPath("data.recordCount").value(1))
                .andExpect(jsonPath("data.scope.q").value("vector   database"));
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"FILTERED\",\"keywordIds\":[\"" + sectionKeyword + "\"]}}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("EMPTY_CHAT_SCOPE"));
        create(UUID.randomUUID(), "{\"scope\":{\"type\":\"FILTERED\",\"q\":\"vector database\"}}")
                .andExpect(status().isUnprocessableEntity());
    }
}
