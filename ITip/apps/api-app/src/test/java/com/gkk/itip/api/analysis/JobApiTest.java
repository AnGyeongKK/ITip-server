package com.gkk.itip.api.analysis;

import com.gkk.itip.common.storage.GcsAccessException;
import com.gkk.itip.common.storage.GcsObjects;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.gkk.itip.common.storage.GcsAccessException.Reason.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"itip.demo.owner-id=demo-user", "itip.analysis.max-caption-bytes=1024"})
@AutoConfigureMockMvc
@Import(JobApiTest.FixedClock.class)
@Testcontainers
class JobApiTest {
    static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    static final String OWNER = "demo-user", VIDEO = "dQw4w9WgXcQ", OTHER_VIDEO = "9bZkp7q19f0";
    static final String BASE = "/api/v1/analysis-jobs";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @TestConfiguration(proxyBeanMethods = false) static class FixedClock {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired VideoRepository videos;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @MockitoBean GcsObjects gcs;

    @BeforeEach void resetDatabase() {
        jdbc.execute("truncate table videos, analysis_batches, idempotency_requests cascade");
        videos.saveAndFlush(new Video(VIDEO)); videos.saveAndFlush(new Video(OTHER_VIDEO));
        when(gcs.metadata(anyString(), anyString(), nullable(Long.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new GcsObjects.Metadata(123, 10, "application/json");
        });
    }

    @Test void admitsFixedGenerationAndReturnsLocationAndNullableResults() throws Exception {
        Fixture f = awaiting(OWNER, VIDEO);
        MvcResult response = submit(UUID.randomUUID(), f);
        assertThat(response.getResponse().getStatus()).isEqualTo(202);
        JsonNode job = data(response);
        assertThat(response.getResponse().getHeader("Location")).isEqualTo(BASE + "/" + job.path("jobId").asString());
        assertThat(job.path("statusUrl").asString()).isEqualTo(response.getResponse().getHeader("Location"));
        assertThat(job.path("batchId").asString()).isEqualTo(f.batch().toString());
        assertThat(job.path("batchItemId").asString()).isEqualTo(f.item().toString());
        assertThat(job.path("status").asString()).isEqualTo("QUEUED");
        assertThat(job.path("analysisId").isNull()).isTrue(); assertThat(job.path("recordId").isNull()).isTrue();
        assertThat(countJobs()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select generation from analysis_jobs", Long.class)).isEqualTo(123L);
        assertThat(jdbc.queryForObject("select status from caption_uploads", String.class)).isEqualTo("CONSUMED");
        mvc.perform(get(job.path("statusUrl").asString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.attempt").value(0)).andExpect(jsonPath("data.maxAttempts").value(3))
                .andExpect(jsonPath("data.video.videoId").value(VIDEO)).andExpect(jsonPath("data.nextPollAfterMs").value(3000));
    }

    @Test void replaysKeyAndUploadButRejectsKeyWithDifferentTypedBody() throws Exception {
        Fixture f = awaiting(OWNER, VIDEO); UUID key = UUID.randomUUID();
        String id = data(submit(key, f)).path("jobId").asString();
        assertThat(data(submit(key, f)).path("jobId").asString()).isEqualTo(id);
        assertThat(data(submit(UUID.randomUUID(), f)).path("jobId").asString()).isEqualTo(id);
        Fixture other = awaiting(OWNER, OTHER_VIDEO);
        error(submit(key, other), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(countJobs()).isEqualTo(1);
    }

    @Test void concurrentSameKeyAndDifferentKeysForSameUploadCreateOneJob() throws Exception {
        for (boolean sameKey : new boolean[]{true, false}) {
            resetDatabase(); Fixture f = awaiting(OWNER, VIDEO); UUID key = UUID.randomUUID();
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { start.await(); return submit(key, f); });
                var second = pool.submit(() -> { start.await(); return submit(sameKey ? key : UUID.randomUUID(), f); });
                start.countDown(); var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
                assertThat(a.getResponse().getStatus()).isEqualTo(202); assertThat(b.getResponse().getStatus()).isEqualTo(202);
                assertThat(data(a).path("jobId").asString()).isEqualTo(data(b).path("jobId").asString());
            }
            assertThat(countJobs()).isEqualTo(1);
        }
    }

    @Test void checksOwnerItemAndVideoBeforeAccessingStorageAndIgnoresUserHeader() throws Exception {
        Fixture other = awaiting("another-user", VIDEO);
        error(submit(UUID.randomUUID(), other), 404, "RESOURCE_NOT_FOUND");
        Fixture mine = awaiting(OWNER, VIDEO);
        error(submit(UUID.randomUUID(), new Fixture(mine.batch(), other.item(), mine.upload())), 404, "RESOURCE_NOT_FOUND");
        jdbc.update("update caption_uploads set video_id=? where id=?", OTHER_VIDEO, mine.upload());
        error(submit(UUID.randomUUID(), mine), 409, "STATE_CONFLICT");
        verifyNoInteractions(gcs);
        mvc.perform(get(BASE + "/" + UUID.randomUUID()).header("X-User-Id", "another-user")).andExpect(status().isNotFound());
    }

    @Test void rejectsExpiredPreparationAndNewUploadForSubmittedItem() throws Exception {
        Fixture expired = awaiting(OWNER, VIDEO);
        jdbc.update("update analysis_batch_items set upload_deadline_at=? where id=?", Timestamp.from(NOW), expired.item());
        error(submit(UUID.randomUUID(), expired), 409, "UPLOAD_NOT_READY");
        Fixture f = awaiting(OWNER, OTHER_VIDEO); submit(UUID.randomUUID(), f);
        UUID newUpload = upload(OWNER, f.item(), OTHER_VIDEO);
        error(submit(UUID.randomUUID(), new Fixture(f.batch(), f.item(), newUpload)), 409, "STATE_CONFLICT");
    }

    @Test void admitsExistingObjectAfterSignedUploadUrlExpires() throws Exception {
        Fixture f = awaiting(OWNER, VIDEO);
        jdbc.update("update caption_uploads set url_expires_at=? where id=?", Timestamp.from(NOW.minusSeconds(60)), f.upload());
        assertThat(submit(UUID.randomUUID(), f).getResponse().getStatus()).isEqualTo(202);
        assertThat(jdbc.queryForObject("select generation from analysis_jobs", Long.class)).isEqualTo(123L);
    }

    @Test void validatesActualSizeContentTypeAndDeclaredMetadata() throws Exception {
        Fixture f = awaiting(OWNER, VIDEO);
        when(gcs.metadata(anyString(), anyString(), nullable(Long.class))).thenReturn(new GcsObjects.Metadata(123, 1025, "application/json"));
        error(submit(UUID.randomUUID(), f), 413, "CAPTION_TOO_LARGE");
        when(gcs.metadata(anyString(), anyString(), nullable(Long.class))).thenReturn(new GcsObjects.Metadata(123, 0, "application/json"));
        error(submit(UUID.randomUUID(), f), 409, "UPLOAD_NOT_READY");
        when(gcs.metadata(anyString(), anyString(), nullable(Long.class))).thenReturn(new GcsObjects.Metadata(123, 10, "text/plain"));
        error(submit(UUID.randomUUID(), f), 409, "UPLOAD_NOT_READY");
        when(gcs.metadata(anyString(), anyString(), nullable(Long.class))).thenReturn(new GcsObjects.Metadata(123, 11, "application/json"));
        error(submit(UUID.randomUUID(), f), 409, "UPLOAD_NOT_READY");
        assertThat(countJobs()).isZero();
    }

    @Test void mapsStorageFailuresWithoutLeakingObjectReferences() throws Exception {
        Fixture f = awaiting(OWNER, VIDEO);
        for (var reason : new GcsAccessException.Reason[]{OBJECT_NOT_FOUND, OBJECT_VERSION_CHANGED, STORAGE_UNAVAILABLE, STORAGE_NOT_CONFIGURED, CAPTION_TOO_LARGE}) {
            when(gcs.metadata(anyString(), anyString(), nullable(Long.class))).thenThrow(new GcsAccessException(reason, true));
            boolean unavailable = reason == STORAGE_UNAVAILABLE || reason == STORAGE_NOT_CONFIGURED;
            MvcResult response = submit(UUID.randomUUID(), f);
            error(response, unavailable ? 503 : reason == CAPTION_TOO_LARGE ? 413 : 409,
                    unavailable ? "STORAGE_UNAVAILABLE" : reason == CAPTION_TOO_LARGE ? "CAPTION_TOO_LARGE" : "UPLOAD_NOT_READY");
            assertThat(response.getResponse().getContentAsString()).doesNotContain("test-bucket", f.upload().toString(), "stackTrace");
            reset(gcs);
        }
    }

    @Test void returnsFailedStateAsHttp200AndHidesDraftAndRecordIdentifiers() throws Exception {
        UUID id = failed(awaiting(OWNER, VIDEO), true);
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk()).andExpect(jsonPath("data.status").value("FAILED"))
                .andExpect(jsonPath("data.error.code").value("AI_UNAVAILABLE")).andExpect(jsonPath("data.error.retryable").value(true))
                .andExpect(jsonPath("data.analysisId").isEmpty()).andExpect(jsonPath("data.recordId").isEmpty())
                .andExpect(jsonPath("data.nextPollAfterMs").isEmpty());
    }

    @Test void manualRetryCreatesNewBatchAndJobAndResetsAttemptsWithIdempotentReplay() throws Exception {
        Fixture original = awaiting(OWNER, VIDEO); UUID oldId = failed(original, true); UUID key = UUID.randomUUID();
        MvcResult response = retry(key, oldId); assertThat(response.getResponse().getStatus()).isEqualTo(202);
        JsonNode job = data(response); String id = job.path("jobId").asString();
        assertThat(id).isNotEqualTo(oldId.toString());
        assertThat(job.path("previousJobId").asString()).isEqualTo(oldId.toString());
        assertThat(job.path("batchId").asString()).isNotEqualTo(original.batch().toString());
        assertThat(response.getResponse().getHeader("Location")).isEqualTo(BASE + "/" + id);
        assertThat(jdbc.queryForObject("select previous_job_id from analysis_jobs where id=?", UUID.class, UUID.fromString(id))).isEqualTo(oldId);
        mvc.perform(get(BASE + "/" + id)).andExpect(jsonPath("data.attempt").value(0)).andExpect(jsonPath("data.maxAttempts").value(3));
        assertThat(data(retry(key, oldId)).path("jobId").asString()).isEqualTo(id);
        error(retry(UUID.randomUUID(), oldId), 409, "JOB_NOT_RETRYABLE");
        error(retry(UUID.randomUUID(), UUID.fromString(id)), 409, "JOB_NOT_RETRYABLE");
        assertThat(countJobs()).isEqualTo(2);
    }

    @Test void retryUsesFixedGenerationAfterSignedUrlExpiryAndRequiresExistingOriginalObject() throws Exception {
        Fixture f = awaiting(OWNER, VIDEO); UUID id = failed(f, true);
        jdbc.update("update caption_uploads set url_expires_at=? where id=?", Timestamp.from(NOW.minusSeconds(60)), f.upload());
        reset(gcs);
        when(gcs.metadata("test-bucket", f.upload() + ".json", 123L)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new GcsObjects.Metadata(123, 10, "application/json");
        });
        assertThat(retry(UUID.randomUUID(), id).getResponse().getStatus()).isEqualTo(202);
        Fixture other = awaiting(OWNER, OTHER_VIDEO); UUID otherId = failed(other, true);
        for (var reason : new GcsAccessException.Reason[]{OBJECT_NOT_FOUND, OBJECT_VERSION_CHANGED}) {
            when(gcs.metadata("test-bucket", other.upload() + ".json", 123L)).thenThrow(new GcsAccessException(reason, false));
            error(retry(UUID.randomUUID(), otherId), 410, "CAPTION_REUPLOAD_REQUIRED");
            reset(gcs);
        }
        assertThat(countJobs()).isEqualTo(3);
    }

    @Test void rejectsRetryForNonRetryableFailureAndSharesKeyScopeAcrossOriginalJobs() throws Exception {
        UUID first = failed(awaiting(OWNER, VIDEO), false);
        error(retry(UUID.randomUUID(), first), 409, "JOB_NOT_RETRYABLE");
        UUID second = failed(awaiting(OWNER, OTHER_VIDEO), true); UUID key = UUID.randomUUID();
        assertThat(retry(key, second).getResponse().getStatus()).isEqualTo(202);
        error(retry(key, first), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test void queuedProcessingAndAutomaticRetryWaitCannotBeManuallyRetried() throws Exception {
        Fixture f = awaiting(OWNER, VIDEO);
        UUID id = UUID.fromString(data(submit(UUID.randomUUID(), f)).path("jobId").asString());
        error(retry(UUID.randomUUID(), id), 409, "JOB_NOT_RETRYABLE");
        UUID token = tx.execute(status -> em.find(AnalysisJob.class, id).claim(NOW, Duration.ofMinutes(1)));
        error(retry(UUID.randomUUID(), id), 409, "JOB_NOT_RETRYABLE");
        tx.executeWithoutResult(status -> em.find(AnalysisJob.class, id).fail(token, "AI_UNAVAILABLE", "분석 서버 연결 실패", true, NOW, Duration.ofSeconds(1)));
        error(retry(UUID.randomUUID(), id), 409, "JOB_NOT_RETRYABLE");
        assertThat(countJobs()).isEqualTo(1);
    }

    @Test void concurrentManualRetriesNeverCreateTwoNewJobs() throws Exception {
        for (boolean sameKey : new boolean[]{true, false}) {
            resetDatabase(); UUID id = failed(awaiting(OWNER, VIDEO), true); UUID key = UUID.randomUUID();
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { start.await(); return retry(key, id); });
                var second = pool.submit(() -> { start.await(); return retry(sameKey ? key : UUID.randomUUID(), id); });
                start.countDown(); var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
                if (sameKey) {
                    assertThat(a.getResponse().getStatus()).isEqualTo(202); assertThat(b.getResponse().getStatus()).isEqualTo(202);
                    assertThat(data(a).path("jobId").asString()).isEqualTo(data(b).path("jobId").asString());
                } else assertThat(java.util.List.of(a.getResponse().getStatus(), b.getResponse().getStatus())).containsExactlyInAnyOrder(202, 409);
            }
            assertThat(countJobs()).isEqualTo(2);
        }
    }

    @Test void validatesSubmitAndRetryInputAndReturns404ForOtherOwners() throws Exception {
        mvc.perform(post(BASE).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        Fixture f = awaiting(OWNER, VIDEO);
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body(f))).andExpect(status().isBadRequest());
        UUID foreign = failed(awaiting("other-owner", OTHER_VIDEO), true);
        error(retry(UUID.randomUUID(), foreign), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(get(BASE + "/" + foreign).header("X-User-Id", "other-owner")).andExpect(status().isNotFound());
    }

    @Test void reusesActiveJobAcrossBatchesAndActualCompletedRecord() throws Exception {
        Fixture first = awaiting(OWNER, VIDEO), second = awaiting(OWNER, VIDEO);
        String id = data(submit(UUID.randomUUID(), first)).path("jobId").asString();
        assertThat(data(submit(UUID.randomUUID(), second)).path("jobId").asString()).isEqualTo(id);
        assertThat(countJobs()).isEqualTo(1);
        UUID record = complete(UUID.fromString(id), null);
        Fixture fresh = awaiting(OWNER, VIDEO);
        MvcResult response = submit(UUID.randomUUID(), fresh);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(response).path("jobId").asString()).isEqualTo(id);
        assertThat(data(response).path("recordId").asString()).isEqualTo(record.toString());
        assertThat(jdbc.queryForObject("select status from analysis_batch_items where id=?", String.class, fresh.item())).isEqualTo("COMPLETED");
        assertThat(countJobs()).isEqualTo(1);
    }

    @Test void deletedRecordDoesNotReuseACompletedJob() throws Exception {
        Fixture first = awaiting(OWNER, VIDEO);
        UUID id = UUID.fromString(data(submit(UUID.randomUUID(), first)).path("jobId").asString());
        UUID record = complete(id, null);
        jdbc.update("delete from analysis_records where id=?", record);
        MvcResult response = submit(UUID.randomUUID(), awaiting(OWNER, VIDEO));
        assertThat(response.getResponse().getStatus()).isEqualTo(202);
        assertThat(data(response).path("jobId").asString()).isNotEqualTo(id.toString());
        assertThat(countJobs()).isEqualTo(2);
    }

    @Test void manualRetryCopiesDraftWithoutExposingItBeforeCompletion() throws Exception {
        UUID oldId = failed(awaiting(OWNER, VIDEO), true);
        UUID draft = tx.execute(status -> {
            var result = AnalysisResult.draft(UUID.randomUUID(), OWNER, VIDEO, oldId, "{\"schemaVersion\":\"1.0\"}", NOW);
            em.persist(result); em.flush(); return result.getId();
        });
        jdbc.update("update analysis_jobs set analysis_id=?,stage='FINISHING' where id=?", draft, oldId);
        UUID newId = UUID.fromString(data(retry(UUID.randomUUID(), oldId)).path("jobId").asString());
        assertThat(jdbc.queryForObject("select analysis_id from analysis_jobs where id=?", UUID.class, newId)).isEqualTo(draft);
        mvc.perform(get(BASE + "/" + newId)).andExpect(status().isOk()).andExpect(jsonPath("data.stage").value("INDEXING"))
                .andExpect(jsonPath("data.analysisId").isEmpty()).andExpect(jsonPath("data.recordId").isEmpty());
        tx.executeWithoutResult(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, newId); job.claim(NOW, Duration.ofMinutes(1));
            assertThat(job.getStage()).isEqualTo(AnalysisStage.INDEXING);
        });
    }

    @Test void retryFindsCompletedJobEvenWhenResultSourceIsPreviousFailedJob() throws Exception {
        UUID oldId = failed(awaiting(OWNER, VIDEO), true);
        UUID completedId = UUID.fromString(data(submit(UUID.randomUUID(), awaiting(OWNER, VIDEO))).path("jobId").asString());
        UUID record = complete(completedId, oldId);
        reset(gcs);
        UUID key = UUID.randomUUID(); MvcResult response = retry(key, oldId);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(response).path("jobId").asString()).isEqualTo(completedId.toString());
        mvc.perform(get(data(response).path("statusUrl").asString())).andExpect(status().isOk())
                .andExpect(jsonPath("data.status").value("COMPLETED")).andExpect(jsonPath("data.recordId").value(record.toString()));
        assertThat(data(retry(key, oldId)).path("jobId").asString()).isEqualTo(completedId.toString());
        assertThat(countJobs()).isEqualTo(2); verifyNoInteractions(gcs);
    }

    @Test void doesNotResurrectDeletedAnalysisThroughOlderFailedJob() throws Exception {
        UUID oldId = failed(awaiting(OWNER, VIDEO), true);
        UUID draft = tx.execute(status -> {
            var result = AnalysisResult.draft(UUID.randomUUID(), OWNER, VIDEO, oldId, "{\"schemaVersion\":\"1.0\"}", NOW);
            em.persist(result); em.flush(); return result.getId();
        });
        jdbc.update("update analysis_jobs set analysis_id=?,stage='INDEXING' where id=?", draft, oldId);
        UUID newId = UUID.fromString(data(retry(UUID.randomUUID(), oldId)).path("jobId").asString());
        UUID recordId = tx.execute(status -> {
            var result = em.find(AnalysisResult.class, draft); result.markReady(NOW);
            var record = AnalysisRecord.fromCompleted(result); em.persist(record); em.flush();
            var job = em.find(AnalysisJob.class, newId);
            UUID token = job.claim(NOW, Duration.ofMinutes(1)); job.changeStage(token, AnalysisStage.FINISHING, NOW);
            job.complete(token, draft, record.getId(), NOW); return record.getId();
        });
        mvc.perform(delete("/api/v1/records/" + recordId)).andExpect(status().isOk());
        clearInvocations(gcs);
        error(retry(UUID.randomUUID(), oldId), 409, "JOB_NOT_RETRYABLE");
        assertThat(countJobs()).isEqualTo(2);
        verifyNoInteractions(gcs);
    }

    @Test void rechecksDeadlineAfterStorageCallBeforeInsertingJob() throws Exception {
        Fixture f = awaiting(OWNER, VIDEO);
        when(gcs.metadata(anyString(), anyString(), nullable(Long.class))).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update("update analysis_batch_items set upload_deadline_at=? where id=?", Timestamp.from(NOW), f.item());
            return new GcsObjects.Metadata(123, 10, "application/json");
        });
        error(submit(UUID.randomUUID(), f), 409, "UPLOAD_NOT_READY"); assertThat(countJobs()).isZero();
    }

    private UUID complete(UUID id, UUID sourceJobId) {
        return tx.execute(status -> {
            AnalysisJob job = em.find(AnalysisJob.class, id);
            UUID token = job.claim(NOW, Duration.ofMinutes(1)); job.changeStage(token, AnalysisStage.FINISHING, NOW);
            var result = AnalysisResult.draft(UUID.randomUUID(), OWNER, VIDEO, sourceJobId == null ? id : sourceJobId, "{\"schemaVersion\":\"1.0\"}", NOW);
            em.persist(result); em.flush(); result.markReady(NOW);
            var record = AnalysisRecord.fromCompleted(result); em.persist(record); em.flush();
            job.complete(token, result.getId(), record.getId(), NOW); return record.getId();
        });
    }

    private record Fixture(UUID batch, UUID item, UUID upload) {}
    private Fixture awaiting(String owner, String video) {
        return tx.execute(status -> {
            var batch = new AnalysisBatch(owner, null, NOW); em.persist(batch); em.flush();
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), video, NOW.plusSeconds(1800), NOW); em.persist(item); em.flush();
            UUID upload = upload(owner, item.getId(), video);
            return new Fixture(batch.getId(), item.getId(), upload);
        });
    }
    private UUID upload(String owner, UUID item, String video) {
        return tx.execute(status -> {
            UUID key = UUID.randomUUID();
            var upload = CaptionUpload.issued(owner, item, video, "test-bucket", key + ".json", "application/json", 10, NOW.plusSeconds(600));
            em.persist(upload); em.flush();
            jdbc.update("update caption_uploads set object_key=? where id=?", upload.getId() + ".json", upload.getId());
            return upload.getId();
        });
    }
    private UUID failed(Fixture f, boolean retryable) {
        return tx.execute(status -> {
            var item = em.find(AnalysisBatchItem.class, f.item());
            var batch = em.find(AnalysisBatch.class, f.batch());
            var upload = em.find(CaptionUpload.class, f.upload()); upload.consume(123);
            var job = AnalysisJob.queued(batch.getOwnerId(), f.batch(), f.item(), item.getVideoId(), f.upload(), 123, null, NOW);
            em.persist(job); em.flush(); item.submit(job.getId(), NOW);
            for (int i = 0; i < (retryable ? 3 : 1); i++) {
                UUID token = job.claim(NOW, Duration.ofMinutes(1));
                job.fail(token, "AI_UNAVAILABLE", "분석 서버에 연결할 수 없어요.", retryable, NOW, Duration.ZERO);
            }
            return job.getId();
        });
    }
    private String body(Fixture f) { return "{\"batchId\":\"" + f.batch() + "\",\"batchItemId\":\"" + f.item() + "\",\"uploadId\":\"" + f.upload() + "\"}"; }
    private MvcResult submit(UUID key, Fixture f) throws Exception { return mvc.perform(post(BASE).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(f))).andReturn(); }
    private MvcResult retry(UUID key, UUID job) throws Exception { return mvc.perform(post(BASE + "/" + job + "/retries").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(); }
    private JsonNode data(MvcResult response) throws Exception { return mapper.readTree(response.getResponse().getContentAsString()).path("data"); }
    private int countJobs() { return jdbc.queryForObject("select count(*) from analysis_jobs", Integer.class); }
    private void error(MvcResult response, int status, String code) throws Exception {
        assertThat(response.getResponse().getStatus()).isEqualTo(status);
        assertThat(mapper.readTree(response.getResponse().getContentAsString()).path("code").asString()).isEqualTo(code);
    }
}
