package com.gkk.itip.chat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import java.util.UUID;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "itip.chat.ai-base-url=http://127.0.0.1:1"})
@AutoConfigureMockMvc
@Testcontainers
class ChatApplicationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;

    @Test void missingEmbeddingConfigurationRejectsBeforeQuestionAdmissionAsJson() throws Exception {
        UUID key=UUID.randomUUID();
        mvc.perform(post("/api/v1/conversations/"+UUID.randomUUID()+"/messages").header("Idempotency-Key",key)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content("{\"clientRequestId\":\""+key+"\",\"content\":\"질문\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("code").value("CHAT_AI_NOT_CONFIGURED"));
    }

    @Test void unknownChatRouteUsesSharedErrorAndRequestId() throws Exception {
        mvc.perform(get("/api/v1/unknown-chat-resource")).andExpect(status().isNotFound())
                .andExpect(jsonPath("code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("data").doesNotExist()).andExpect(header().exists("X-Request-Id"));
    }
}
