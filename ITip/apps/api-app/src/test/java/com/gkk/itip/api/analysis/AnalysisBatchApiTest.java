package com.gkk.itip.api.analysis;

import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.domain.video.VideoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "itip.demo.owner-id=demo-user")
@AutoConfigureMockMvc
@Import(AnalysisBatchApiTest.FixedClock.class)
@Testcontainers
class AnalysisBatchApiTest {
    static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    static final String OWNER = "demo-user";
    static final String V1 = "dQw4w9WgXcQ";
    static final String V2 = "9bZkp7q19f0";
    static final String V3 = "abcdefghijk";
    static final String BASE = "/api/v1/analysis-batches";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired VideoRepository videos;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    @BeforeEach void resetDatabase() {
        jdbc.execute("truncate table videos, analysis_batches, idempotency_requests cascade");
        videos.saveAndFlush(new Video(V1));
        videos.saveAndFlush(new Video(V2));
        videos.saveAndFlush(new Video(V3));
    }

    @Test void createsOneItemPerUniqueVideoWithNullableFieldsAndLocation() throws Exception {
        MvcResult result = create(UUID.randomUUID(), selection(V1, V1));
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = data(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo(BASE + "/" + data.path("batchId").asString());
        assertThat(data.path("status").asString()).isEqualTo("PREPARING");
        assertThat(data.path("items").size()).isEqualTo(1);
        assertThat(data.path("items").get(0).path("action").asString()).isEqualTo("COLLECT_CAPTIONS");
        assertThat(data.path("items").get(0).has("jobId")).isTrue();
        assertThat(data.path("items").get(0).path("jobId").isNull()).isTrue();
        assertThat(data.path("items").get(0).path("analysisId").isNull()).isTrue();
        assertThat(data.path("items").get(0).path("uploadDeadlineAt").asString()).isEqualTo(NOW.plusSeconds(1800).toString());
        assertThat(data.path("selectionSummary").path("uniqueVideoCount").asInt()).isEqualTo(1);
        assertThat(data.path("selectionSummary").path("newAnalysisCount").asInt()).isEqualTo(1);
        assertThat(data.has("quota")).isFalse();
    }

    @Test void replaysSameKeyAndRejectsDifferentBodyIncludingDuplicateChanges() throws Exception {
        UUID key = UUID.randomUUID();
        String first = data(create(key, selection(V1, V1))).path("batchId").asString();
        assertThat(data(create(key, selection(V1, V1))).path("batchId").asString()).isEqualTo(first);
        MvcResult conflict = create(key, selection(V1));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(mapper.readTree(conflict.getResponse().getContentAsString()).path("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject("select count(*) from analysis_batches", Integer.class)).isEqualTo(1);
    }

    @Test void concurrentRetransmissionCreatesExactlyOneBatch() throws Exception {
        UUID key = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return create(key, selection(V1)); });
            var second = pool.submit(() -> { start.await(); return create(key, selection(V1)); });
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.getResponse().getStatus()).isEqualTo(201);
            assertThat(b.getResponse().getStatus()).isEqualTo(201);
            assertThat(data(a).path("batchId").asString()).isEqualTo(data(b).path("batchId").asString());
        }
        assertThat(jdbc.queryForObject("select count(*) from analysis_batches", Integer.class)).isEqualTo(1);
    }

    @Test void reusesPersonalCompletedRecordAndRunningJob() throws Exception {
        Fixture completed = fixture(OWNER, V1, true);
        Fixture active = fixture(OWNER, V2, false);
        JsonNode created = data(create(UUID.randomUUID(), selection(V1, V2, V3)));
        assertThat(created.path("selectionSummary").path("existingResultCount").asInt()).isEqualTo(1);
        assertThat(created.path("selectionSummary").path("existingJobCount").asInt()).isEqualTo(1);
        assertThat(created.path("selectionSummary").path("newAnalysisCount").asInt()).isEqualTo(1);
        JsonNode resultItem = itemFor(created, V1);
        assertThat(resultItem.path("action").asString()).isEqualTo("OPEN_RESULT");
        assertThat(resultItem.path("recordId").asString()).isEqualTo(completed.recordId().toString());
        assertThat(itemFor(created, V2).path("jobId").asString()).isEqualTo(active.jobId().toString());
    }

    @Test void rejectsAnotherPreparationButAllowsAnExpiredPreparation() throws Exception {
        assertThat(create(UUID.randomUUID(), selection(V1)).getResponse().getStatus()).isEqualTo(201);
        assertThat(create(UUID.randomUUID(), selection(V1)).getResponse().getStatus()).isEqualTo(409);
        tx.executeWithoutResult(status -> {
            var batch = new AnalysisBatch(OWNER, null, NOW.minusSeconds(3600)); em.persist(batch);
            em.persist(AnalysisBatchItem.collectCaptions(batch.getId(), V2, NOW.minusSeconds(1), NOW.minusSeconds(3600)));
        });
        assertThat(create(UUID.randomUUID(), selection(V2)).getResponse().getStatus()).isEqualTo(201);
    }

    @Test void onlyReadsConfiguredOwnerAndIgnoresSpoofedUserHeader() throws Exception {
        Fixture other = fixture("another-user", V1, false);
        mvc.perform(get(BASE + "/" + other.batchId()).header("X-User-Id", "another-user"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(patch(BASE + "/" + other.batchId() + "/items/" + other.itemId())
                        .contentType(MediaType.APPLICATION_JSON).content(failure()))
                .andExpect(status().isNotFound());
    }

    @Test void failsOnlyAwaitingUploadAndKeepsFailureAsAJobErrorPayload() throws Exception {
        JsonNode created = data(create(UUID.randomUUID(), selection(V1)));
        String url = BASE + "/" + created.path("batchId").asString() + "/items/" + created.path("items").get(0).path("itemId").asString();
        mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(failure()))
                .andExpect(status().isOk()).andExpect(jsonPath("data.status").value("FAILED"))
                .andExpect(jsonPath("data.error.code").value("KOREAN_CAPTION_NOT_FOUND"))
                .andExpect(jsonPath("data.error.retryable").value(false))
                .andExpect(jsonPath("data.quotaReleased").doesNotExist());
        mvc.perform(patch(url).contentType(MediaType.APPLICATION_JSON).content(failure()))
                .andExpect(status().isConflict());
        Fixture active = fixture(OWNER, V2, false);
        mvc.perform(patch(BASE + "/" + active.batchId() + "/items/" + active.itemId())
                        .contentType(MediaType.APPLICATION_JSON).content(failure()))
                .andExpect(status().isConflict());
    }

    @Test void aggregatesActualJobStatesAndTerminalCountsFromReturnedItems() throws Exception {
        Fixture completed = fixture(OWNER, V1, true);
        JsonNode created = data(create(UUID.randomUUID(), selection(V1, V2)));
        String batchId = created.path("batchId").asString();
        String failedId = itemFor(created, V2).path("itemId").asString();
        mvc.perform(patch(BASE + "/" + batchId + "/items/" + failedId).contentType(MediaType.APPLICATION_JSON).content(failure()))
                .andExpect(status().isOk());
        JsonNode batch = data(mvc.perform(get(BASE + "/" + batchId)).andExpect(status().isOk()).andReturn());
        assertThat(batch.path("status").asString()).isEqualTo("PARTIALLY_FAILED");
        assertThat(batch.path("counts").path("total").asInt()).isEqualTo(2);
        assertThat(batch.path("counts").path("completed").asInt()).isEqualTo(1);
        assertThat(batch.path("counts").path("failed").asInt()).isEqualTo(1);
        assertThat(batch.path("nextPollAfterMs").isNull()).isTrue();
        assertThat(itemFor(batch, V1).path("recordId").asString()).isEqualTo(completed.recordId().toString());
    }

    @Test void linkedJobFromAnotherBatchProvidesCurrentStageAndNullableVideoFields() throws Exception {
        Fixture active = fixture(OWNER, V1, false);
        JsonNode created = data(create(UUID.randomUUID(), selection(V1)));
        tx.executeWithoutResult(status -> {
            var job = em.find(AnalysisJob.class, active.jobId());
            UUID token = job.claim(NOW, Duration.ofMinutes(10));
            job.changeStage(token, AnalysisStage.SUMMARIZING, NOW);
        });
        JsonNode batch = data(mvc.perform(get(BASE + "/" + created.path("batchId").asString())).andExpect(status().isOk()).andReturn());
        assertThat(batch.path("status").asString()).isEqualTo("PROCESSING");
        assertThat(batch.path("counts").path("processing").asInt()).isEqualTo(1);
        JsonNode item = batch.path("items").get(0);
        assertThat(item.path("status").asString()).isEqualTo("PROCESSING");
        assertThat(item.path("stage").asString()).isEqualTo("SUMMARIZING");
        assertThat(item.path("video").has("title")).isTrue();
        assertThat(item.path("video").path("title").isNull()).isTrue();
        assertThat(item.path("uploadDeadlineAt").isNull()).isTrue();
    }

    @Test void validatesBodyHeaderAndMissingVideo() throws Exception {
        for (String body : new String[]{"{}", "{\"videoIds\":[]}", "{\"videoIds\":[null]}", selection("invalid")})
            assertThat(create(UUID.randomUUID(), body).getResponse().getStatus()).isEqualTo(400);
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(selection(V1))).andExpect(status().isBadRequest());
        mvc.perform(post(BASE).header("Idempotency-Key", "bad-key").contentType(MediaType.APPLICATION_JSON).content(selection(V1)))
                .andExpect(status().isBadRequest());
        assertThat(create(UUID.randomUUID(), selection("xxxxxxxxxxx")).getResponse().getStatus()).isEqualTo(404);
        mvc.perform(get(BASE + "/bad-id")).andExpect(status().isBadRequest());
    }

    @Test void exposesPlaylistIdentityWithoutFabricatingMetadata() throws Exception {
        JsonNode batch = data(create(UUID.randomUUID(), "{\"videoIds\":[\"" + V1 + "\"],\"playlistId\":\"PL-test\"}"));
        JsonNode read = data(mvc.perform(get(BASE + "/" + batch.path("batchId").asString())).andExpect(status().isOk()).andReturn());
        assertThat(read.path("playlist").path("playlistId").asString()).isEqualTo("PL-test");
        assertThat(read.path("playlist").has("title")).isTrue();
        assertThat(read.path("playlist").path("title").isNull()).isTrue();
        assertThat(read.path("playlist").path("totalItemCount").isNull()).isTrue();
    }

    private MvcResult create(UUID key, String body) throws Exception {
        return mvc.perform(post(BASE).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }
    private String selection(String... ids) { return "{\"videoIds\":[\"" + String.join("\",\"", ids) + "\"],\"playlistId\":null}"; }
    private String failure() { return "{\"collectionFailure\":{\"code\":\"KOREAN_CAPTION_NOT_FOUND\"}}"; }
    private JsonNode data(MvcResult result) throws Exception { return mapper.readTree(result.getResponse().getContentAsString()).path("data"); }
    private JsonNode itemFor(JsonNode data, String videoId) {
        for (JsonNode item : data.path("items")) if (videoId.equals(item.path("videoId").asString())) return item;
        throw new AssertionError("video item not found: " + videoId);
    }

    private record Fixture(UUID batchId, UUID itemId, UUID jobId, UUID recordId) {}
    private Fixture fixture(String owner, String videoId, boolean complete) {
        return tx.execute(status -> {
            var batch = new AnalysisBatch(owner, null, NOW); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), videoId, NOW.plusSeconds(1800), NOW); em.persist(item); em.flush();
            var upload = CaptionUpload.issued(owner, item.getId(), videoId, "test-bucket", UUID.randomUUID()+".json", "application/json", 10, NOW.plusSeconds(600));
            em.persist(upload); em.flush();
            var job = AnalysisJob.queued(owner, batch.getId(), item.getId(), videoId, upload.getId(), 1, null, NOW); em.persist(job); em.flush();
            item.submit(job.getId(), NOW);
            UUID recordId = null;
            if (complete) {
                UUID token = job.claim(NOW, Duration.ofHours(1)); job.changeStage(token, AnalysisStage.FINISHING, NOW);
                var result = AnalysisResult.draft(UUID.randomUUID(), owner, videoId, job.getId(), "{\"schemaVersion\":\"1.0\"}", NOW); em.persist(result); em.flush();
                result.markReady(NOW);
                var record = AnalysisRecord.fromCompleted(result); em.persist(record); em.flush(); recordId = record.getId();
                job.complete(token, result.getId(), recordId, NOW);
            }
            return new Fixture(batch.getId(), item.getId(), job.getId(), recordId);
        });
    }
}
