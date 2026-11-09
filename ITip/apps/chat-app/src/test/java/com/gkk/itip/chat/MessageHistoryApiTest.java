package com.gkk.itip.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "itip.demo.owner-id=demo-user"})
@AutoConfigureMockMvc
@Testcontainers
class MessageHistoryApiTest {
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
    @BeforeEach void clear() { f = new ChatTestData(jdbc); f.clear(); }
    String path(UUID room) { return "/api/v1/conversations/" + room + "/messages"; }

    @Test void returnsNewestPageInConversationOrderEvenWithIdenticalTimestamps() throws Exception {
        UUID room = f.room("demo-user"), request = UUID.randomUUID();
        UUID first = f.question(room, UUID.randomUUID(), 1, "첫 질문");
        UUID second = f.question(room, request, 2, "최근 질문");
        UUID answer = f.answer(room, request, second, 3, "COMPLETED", "저장된 답변", 4);
        var response = mvc.perform(get(path(room)).param("limit", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].messageId").value(second.toString()))
                .andExpect(jsonPath("data.items[0].role").value("USER"))
                .andExpect(jsonPath("data.items[0].scopeRecordCount").doesNotExist())
                .andExpect(jsonPath("data.items[1].messageId").value(answer.toString()))
                .andExpect(jsonPath("data.items[1].scopeRecordCount").value(4))
                .andExpect(jsonPath("data.hasNext").value(true)).andReturn();
        String cursor = mapper.readTree(response.getResponse().getContentAsString()).path("data").path("nextCursor").asString();
        mvc.perform(get(path(room)).param("limit", "2").param("cursor", cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.items[0].messageId").value(first.toString()))
                .andExpect(jsonPath("data.nextCursor").isEmpty()).andExpect(jsonPath("data.hasNext").value(false));
    }

    @Test void restoresBothMessagesByClientRequestIdWithoutCreatingAnotherRequest() throws Exception {
        UUID room = f.room("demo-user"), request = UUID.randomUUID();
        UUID q = f.question(room, request, 1, "복구 질문");
        UUID a = f.answer(room, request, q, 2, "GENERATING", "부분 답변", 2);
        mvc.perform(get(path(room)).param("clientRequestId", request.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(2))
                .andExpect(jsonPath("data.items[0].messageId").value(q.toString()))
                .andExpect(jsonPath("data.items[1].messageId").value(a.toString()))
                .andExpect(jsonPath("data.items[1].content").value("부분 답변"))
                .andExpect(jsonPath("data.hasNext").value(false));
        assertThat(jdbc.queryForObject("select count(*) from chat_messages", Integer.class)).isEqualTo(2);
    }

    @Test void individualAnswerRestoresIncompleteStateAndRetryInformation() throws Exception {
        UUID room = f.room("demo-user"), request = UUID.randomUUID();
        UUID a = f.answer(room, request, f.question(room, request, 1, "질문"), 2, "INCOMPLETE", "부분 답변", 3);
        mvc.perform(get(path(room) + "/" + a)).andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("INCOMPLETE"))
                .andExpect(jsonPath("data.content").value("부분 답변"))
                .andExpect(jsonPath("data.error.code").value("CHAT_STREAM_FAILED"))
                .andExpect(jsonPath("data.retryable").value(true))
                .andExpect(jsonPath("data.nextPollAfterMs").isEmpty())
                .andExpect(jsonPath("data.completedAt").isEmpty()).andExpect(jsonPath("data.finishReason").isEmpty());
    }

    @Test void deletingOriginalRecordKeepsTextAndCountsButMarksSourcesDeleted() throws Exception {
        var original = f.analysis("demo-user", "원본 영상");
        UUID room = f.room("demo-user"), request = UUID.randomUUID();
        UUID a = f.answer(room, request, f.question(room, request, 1, "원래 질문"), 2, "COMPLETED", "원래 답변", 4);
        f.source(a, original);
        mvc.perform(get(path(room) + "/" + a)).andExpect(status().isOk())
                .andExpect(jsonPath("data.sources[0].availability").value("AVAILABLE"));
        jdbc.update("delete from analysis_records where id=?", original.recordId());
        mvc.perform(get(path(room) + "/" + a)).andExpect(status().isOk())
                .andExpect(jsonPath("data.content").value("원래 답변"))
                .andExpect(jsonPath("data.scopeRecordCount").value(4)).andExpect(jsonPath("data.usedRecordCount").value(1))
                .andExpect(jsonPath("data.sources[0].availability").value("DELETED"))
                .andExpect(jsonPath("data.sources[0].title").value("출처 원본 제목"))
                .andExpect(jsonPath("data.sources[0].analyzedAt").value("2026-11-09T00:00:00Z"));
    }

    @Test void hidesOtherRoomsAndMessageIdsEvenWhenUserHeaderIsForged() throws Exception {
        UUID mine = f.room("demo-user"), other = f.room("other-user");
        UUID message = f.question(other, UUID.randomUUID(), 1, "비공개 질문");
        mvc.perform(get(path(other)).header("X-User-Id", "other-user")).andExpect(status().isNotFound());
        mvc.perform(get(path(mine) + "/" + message)).andExpect(status().isNotFound());
        mvc.perform(get(path(UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test void emptyHistoryAndUnknownRequestReturnEmptyPages() throws Exception {
        UUID room = f.room("demo-user");
        mvc.perform(get(path(room))).andExpect(status().isOk()).andExpect(jsonPath("data.items").isEmpty())
                .andExpect(jsonPath("data.nextCursor").isEmpty()).andExpect(jsonPath("data.hasNext").value(false));
        mvc.perform(get(path(room)).param("clientRequestId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("data.items").isEmpty());
    }

    @Test void invalidPagingOrConflictingRecoveryParametersReturnTypedErrors() throws Exception {
        UUID room = f.room("demo-user");
        for (String limit : new String[]{"0", "51", "bad"})
            mvc.perform(get(path(room)).param("limit", limit)).andExpect(status().isBadRequest());
        mvc.perform(get(path(room)).param("cursor", "bad")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value("INVALID_CURSOR"));
        mvc.perform(get(path(room)).param("cursor", "bad").param("clientRequestId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
    }
}
