package com.gkk.itip.worker.analysis;

import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.worker.analysis.caption.CaptionChunker.Chunk;
import com.gkk.itip.domain.analysis.result.ResultDocument;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"spring.flyway.enabled=true", "itip.worker.scheduling-enabled=false"})
@Testcontainers
class WorkerResultStoreTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired WorkerResultStore store;
    @Autowired JobQueue queue;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired AnalysisJobRepository jobs;
    @Autowired AnalysisResultRepository results;
    @Autowired AnalysisSectionRepository sections;
    @Autowired AnalysisRecordRepository records;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    private static final String OWNER = "store-test";
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @BeforeEach void clear() { jdbc.execute("truncate analysis_batches, videos cascade"); }

    @Test void savesScreenJsonAndSectionIdsAtomicallyAndReusesTheFirstDraft() {
        var claim = fixture(null);
        var document = document(UUID.randomUUID());
        assertThat(store.draft(claim, NOW)).isEmpty();
        var draft = store.saveDraft(claim, document, NOW.plusSeconds(1));
        assertThat(draft.document()).isEqualTo(document);
        assertThat(draft.indexed()).isFalse();
        assertThat(results.findById(draft.analysisId()).orElseThrow().isChatReady()).isFalse();
        assertThat(mapper.readValue(results.findById(draft.analysisId()).orElseThrow().getResultJson(), ResultDocument.class))
                .isEqualTo(document);
        var saved = sections.findAllByAnalysisIdOrderByStartMsAscIdAsc(draft.analysisId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getId().toString()).isEqualTo(document.sections().getFirst().sectionId());
        assertThat(mapper.readValue(saved.getFirst().getDetailJson(), ResultDocument.Detail.class))
                .isEqualTo(document.sections().getFirst().detail());
        var job = jobs.findById(claim.jobId()).orElseThrow();
        assertThat(job.getAnalysisId()).isEqualTo(draft.analysisId());
        assertThat(job.getStage()).isEqualTo(AnalysisStage.INDEXING);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(store.saveDraft(claim, document(UUID.randomUUID()), NOW.plusSeconds(2))).isEqualTo(draft);
        assertThat(store.draft(claim, NOW.plusSeconds(2))).contains(draft);
        assertThat(results.count()).isEqualTo(1);
        assertThat(sections.count()).isEqualTo(1);
        assertThat(records.count()).isZero();
    }

    @Test void sourceUsesConsumedGenerationEvenAfterTheUrlExpiresAndKeepsUnknownDurationNull() {
        var claim = fixture(null);
        var source = store.source(claim, NOW.plusSeconds(2));
        assertThat(source.bucket()).isEqualTo("test-bucket");
        assertThat(source.objectKey()).endsWith(".json");
        assertThat(source.generation()).isEqualTo(7);
        assertThat(source.durationMs()).isNull();
        assertThat(source.declaredSizeBytes()).isEqualTo(100);
    }

    @Test void sourceReturnsStoredDurationAndRejectsAnUnconsumedOrDifferentGenerationUpload() {
        var claim = fixture(10_000L);
        assertThat(store.source(claim, NOW).durationMs()).isEqualTo(10_000L);
        jdbc.update("update caption_uploads set generation = 8 where id = ?", claim.uploadId());
        assertThatIllegalStateException().isThrownBy(() -> store.source(claim, NOW));
        jdbc.update("update caption_uploads set generation = 7, status = 'UPLOADED' where id = ?", claim.uploadId());
        assertThatIllegalStateException().isThrownBy(() -> store.source(claim, NOW));
    }

    @Test void persistsCaptionVectorsAndArraysAndReusesTheAtomicIndexWithoutOverwriting() {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        var chunk = chunk(draft, UUID.randomUUID(), "실제 자막 원문");
        store.saveIndex(claim, draft.analysisId(), List.of(chunk), "embedding-v1", List.of(new float[]{1, 2, 3}), NOW);
        var row = jdbc.queryForMap("select *, embedding::text as vector_text, vector_dims(embedding) as dimensions from analysis_chunks");
        assertThat(row.get("text")).isEqualTo("실제 자막 원문");
        assertThat(row.get("owner_id")).isEqualTo(OWNER);
        assertThat(row.get("analysis_id")).isEqualTo(draft.analysisId());
        assertThat(row.get("source_type")).isEqualTo("CAPTION");
        assertThat(row.get("embedding_model")).isEqualTo("embedding-v1");
        assertThat(row.get("embedding_dimensions")).isEqualTo(3);
        assertThat(row.get("dimensions")).isEqualTo(3);
        assertThat(row.get("vector_text")).isEqualTo("[1,2,3]");
        jdbc.query("select section_ids, source_segment_ids, start_ms, end_ms from analysis_chunks", rs -> {
            assertThat((Object[]) rs.getArray(1).getArray()).containsExactly(UUID.fromString(draft.document().sections().getFirst().sectionId()));
            assertThat((Object[]) rs.getArray(2).getArray()).containsExactly("s000001", "s000002");
            assertThat(rs.getLong(3)).isZero();
            assertThat(rs.getLong(4)).isEqualTo(2_000);
        });
        assertThat(store.draft(claim, NOW).orElseThrow().indexed()).isTrue();
        queue.advance(claim.jobId(), claim.runToken(), AnalysisStage.FINISHING, NOW);
        store.saveIndex(claim, draft.analysisId(), List.of(chunk(draft, UUID.randomUUID(), "덮어쓰면 안 됨")),
                "embedding-v2", List.of(new float[]{2, 3}), NOW);
        assertThat(chunkCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select text from analysis_chunks", String.class)).isEqualTo("실제 자막 원문");
        assertThat(jobs.findById(claim.jobId()).orElseThrow().getStage()).isEqualTo(AnalysisStage.FINISHING);
    }

    static Stream<List<float[]>> invalidVectors() {
        return Stream.of(List.<float[]>of(), List.of(new float[]{}), List.of(new float[]{Float.NaN}),
                List.of(new float[]{Float.POSITIVE_INFINITY}), List.of(new float[]{0, 0}),
                List.of(new float[]{1}, new float[]{2, 3}));
    }

    @ParameterizedTest @MethodSource("invalidVectors")
    void rejectsInvalidEmbeddingCountsDimensionsAndValuesWithoutWriting(List<float[]> vectors) {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        var chunks = vectors.size() == 2 ? List.of(chunk(draft, UUID.randomUUID(), "첫 원문"), chunk(draft, UUID.randomUUID(), "둘째 원문"))
                : List.of(chunk(draft, UUID.randomUUID(), "원문"));
        assertThatIllegalArgumentException().isThrownBy(() -> store.saveIndex(claim, draft.analysisId(), chunks, "model", vectors, NOW));
        assertThat(chunkCount()).isZero();
        assertThat(store.draft(claim, NOW).orElseThrow().indexed()).isFalse();
    }

    @Test void rejectsEmptyIndexBadModelAndForeignSectionReference() {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        var chunk = chunk(draft, UUID.randomUUID(), "원문");
        assertThatIllegalArgumentException().isThrownBy(() -> store.saveIndex(claim, draft.analysisId(), List.of(), "model", List.of(), NOW));
        for (String model : List.of(" ", "x".repeat(256))) {
            assertThatIllegalArgumentException().isThrownBy(() -> store.saveIndex(claim, draft.analysisId(), List.of(chunk), model, List.of(new float[]{1}), NOW));
        }
        var foreign = new Chunk(UUID.randomUUID(), List.of(UUID.randomUUID()), List.of("s000001"), "원문", 0, 1_000);
        assertThatIllegalArgumentException().isThrownBy(() -> store.saveIndex(claim, draft.analysisId(), List.of(foreign), "model", List.of(new float[]{1}), NOW));
        assertThat(chunkCount()).isZero();
    }

    @Test void databaseFailureDuringBatchInsertRollsBackEveryChunk() {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        UUID duplicate = UUID.randomUUID();
        assertThatThrownBy(() -> store.saveIndex(claim, draft.analysisId(),
                List.of(chunk(draft, duplicate, "첫 원문"), chunk(draft, duplicate, "둘째 원문")),
                "model", List.of(new float[]{1}, new float[]{2}), NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(chunkCount()).isZero();
    }

    @Test void refusesCompletionUntilSectionsAndAnAtomicIndexAreReady() {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        assertThatIllegalStateException().isThrownBy(() -> store.complete(claim, draft.analysisId(), NOW));
        assertUnfinished(claim, draft.analysisId());
        store.saveIndex(claim, draft.analysisId(), List.of(chunk(draft, UUID.randomUUID(), "원문")), "model", List.of(new float[]{1}), NOW);
        jdbc.update("delete from analysis_sections where analysis_id = ?", draft.analysisId());
        assertThatIllegalStateException().isThrownBy(() -> store.complete(claim, draft.analysisId(), NOW));
        assertUnfinished(claim, draft.analysisId());
    }

    @Test void completesResultRecordAndJobTogetherAndDuplicateCompletionAddsNothing() {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        store.saveIndex(claim, draft.analysisId(), List.of(chunk(draft, UUID.randomUUID(), "원문")), "model", List.of(new float[]{1}), NOW);
        UUID recordId = store.complete(claim, draft.analysisId(), NOW.plusSeconds(1));
        var result = results.findById(draft.analysisId()).orElseThrow();
        assertThat(result.isChatReady()).isTrue();
        assertThat(result.getCompletedAt()).isEqualTo(NOW.plusSeconds(1));
        var record = records.findById(recordId).orElseThrow();
        assertThat(record.getAnalysisId()).isEqualTo(draft.analysisId());
        assertThat(record.getOwnerId()).isEqualTo(OWNER);
        var job = jobs.findById(claim.jobId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getStage()).isEqualTo(AnalysisStage.FINISHING);
        assertThat(job.getRecordId()).isEqualTo(recordId);
        assertThatThrownBy(() -> store.complete(claim, draft.analysisId(), NOW.plusSeconds(2))).isInstanceOf(LostJobLeaseException.class);
        assertThat(records.count()).isEqualTo(1);
        assertThat(results.count()).isEqualTo(1);
        assertThat(chunkCount()).isEqualTo(1);
    }

    @Test void recordInsertFailureRollsBackReadyFlagAndFinishingStage() {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        store.saveIndex(claim, draft.analysisId(), List.of(chunk(draft, UUID.randomUUID(), "원문")), "model", List.of(new float[]{1}), NOW);
        jdbc.update("insert into analysis_records(id,owner_id,video_id,analysis_id,analyzed_at) values(?,?,?,?,?)",
                UUID.randomUUID(), OWNER, claim.videoId(), draft.analysisId(), java.sql.Timestamp.from(NOW));
        assertThatThrownBy(() -> store.complete(claim, draft.analysisId(), NOW)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(results.findById(draft.analysisId()).orElseThrow().isChatReady()).isFalse();
        assertThat(jobs.findById(claim.jobId()).orElseThrow().getStage()).isEqualTo(AnalysisStage.INDEXING);
        assertThat(jobs.findById(claim.jobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(records.count()).isEqualTo(1);
    }

    @Test void expiredAndReclaimedLeaseRejectsAllOldWorkerWritesAndReads() {
        var old = fixture(null);
        var draft = store.saveDraft(old, document(UUID.randomUUID()), NOW);
        assertThatThrownBy(() -> store.saveDraft(old, document(UUID.randomUUID()), NOW.plusSeconds(30))).isInstanceOf(LostJobLeaseException.class);
        var current = queue.claim(NOW.plusSeconds(30), LEASE).orElseThrow();
        assertThat(current.runToken()).isNotEqualTo(old.runToken());
        var chunks = List.of(chunk(draft, UUID.randomUUID(), "원문"));
        Instant later = NOW.plusSeconds(31);
        assertThatThrownBy(() -> store.draft(old, later)).isInstanceOf(LostJobLeaseException.class);
        assertThatThrownBy(() -> store.source(old, later)).isInstanceOf(LostJobLeaseException.class);
        assertThatThrownBy(() -> store.saveDraft(old, document(UUID.randomUUID()), later)).isInstanceOf(LostJobLeaseException.class);
        assertThatThrownBy(() -> store.saveIndex(old, draft.analysisId(), chunks, "model", List.of(new float[]{1}), later)).isInstanceOf(LostJobLeaseException.class);
        assertThatThrownBy(() -> store.complete(old, draft.analysisId(), later)).isInstanceOf(LostJobLeaseException.class);
        assertThatThrownBy(() -> queue.advance(old.jobId(), old.runToken(), AnalysisStage.FINISHING, later)).isInstanceOf(LostJobLeaseException.class);
        assertThat(store.draft(current, later).orElseThrow().analysisId()).isEqualTo(draft.analysisId());
        assertThat(results.count()).isEqualTo(1);
        assertThat(sections.count()).isEqualTo(1);
        assertThat(chunkCount()).isZero();
        assertThat(records.count()).isZero();
    }

    @Test void freshJobCannotBorrowAnExistingDraftFromADifferentOwnerOrVideo() {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        jdbc.update("update analysis_results set owner_id = 'different-owner' where id = ?", draft.analysisId());
        assertThatThrownBy(() -> store.draft(claim, NOW)).isInstanceOf(IllegalStateException.class).isNotInstanceOf(LostJobLeaseException.class);
        assertThatThrownBy(() -> store.saveIndex(claim, draft.analysisId(), List.of(chunk(draft, UUID.randomUUID(), "원문")),
                "model", List.of(new float[]{1}), NOW)).isInstanceOf(IllegalStateException.class).isNotInstanceOf(LostJobLeaseException.class);
        assertThat(chunkCount()).isZero();
    }

    @Test void mixedEmbeddingModelsOrDimensionsAreNeverReportedReadyOrCompleted() {
        var claim = fixture(null);
        var draft = store.saveDraft(claim, document(UUID.randomUUID()), NOW);
        store.saveIndex(claim, draft.analysisId(), List.of(chunk(draft, UUID.randomUUID(), "첫 원문"), chunk(draft, UUID.randomUUID(), "둘째 원문")),
                "model", List.of(new float[]{1}, new float[]{2}), NOW);
        jdbc.update("update analysis_chunks set embedding_model = 'other' where id = (select id from analysis_chunks limit 1)");
        assertThat(store.draft(claim, NOW).orElseThrow().indexed()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> store.complete(claim, draft.analysisId(), NOW));
        jdbc.update("update analysis_chunks set embedding_model = 'model', embedding = '[1,2]'::vector, embedding_dimensions = 2 where id = (select id from analysis_chunks limit 1)");
        assertThat(store.draft(claim, NOW).orElseThrow().indexed()).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> store.complete(claim, draft.analysisId(), NOW));
        assertUnfinished(claim, draft.analysisId());
    }

    @Test void manualRetryReusesThePrivateDraftAndConsumedUploadFromThePreviousItem() {
        var previous = fixture(null);
        var draft = store.saveDraft(previous, document(UUID.randomUUID()), NOW);
        queue.fail(previous.jobId(), previous.runToken(), "EMBEDDING_FAILED", "실패", false, NOW.plusSeconds(1), Duration.ZERO);
        var retry = retry(previous);
        assertThat(retry.batchItemId()).isNotEqualTo(previous.batchItemId());
        assertThat(store.source(retry, NOW.plusSeconds(2)).generation()).isEqualTo(7);
        assertThat(store.draft(retry, NOW.plusSeconds(2))).contains(draft);
        assertThat(store.saveDraft(retry, document(UUID.randomUUID()), NOW.plusSeconds(2))).isEqualTo(draft);
        assertThat(results.count()).isEqualTo(1);
        store.saveIndex(retry, draft.analysisId(), List.of(chunk(draft, UUID.randomUUID(), "원문")), "model", List.of(new float[]{1}), NOW.plusSeconds(2));
        store.complete(retry, draft.analysisId(), NOW.plusSeconds(2));
        assertThat(records.count()).isEqualTo(1);
    }

    @Test void manualRetryCannotRestoreAReadyResultEvenWhenAnOldFailedJobReferencesIt() {
        var previous = fixture(null);
        var draft = store.saveDraft(previous, document(UUID.randomUUID()), NOW);
        queue.fail(previous.jobId(), previous.runToken(), "FAILED", "실패", false, NOW.plusSeconds(1), Duration.ZERO);
        tx.executeWithoutResult(status -> results.findById(draft.analysisId()).orElseThrow().markReady(NOW.plusSeconds(1)));
        var retry = retry(previous);
        assertThatIllegalStateException().isThrownBy(() -> store.draft(retry, NOW.plusSeconds(2)));
        assertThatIllegalStateException().isThrownBy(() -> store.saveDraft(retry, document(UUID.randomUUID()), NOW.plusSeconds(2)));
        assertThatIllegalStateException().isThrownBy(() -> store.saveIndex(retry, draft.analysisId(), List.of(chunk(draft, UUID.randomUUID(), "원문")),
                "model", List.of(new float[]{1}), NOW.plusSeconds(2)));
        assertThatIllegalStateException().isThrownBy(() -> store.complete(retry, draft.analysisId(), NOW.plusSeconds(2)));
        assertThat(records.count()).isZero();
    }

    @Test void invalidDraftSectionsRollbackTheResultAndJobAttachment() {
        var claim = fixture(null);
        var valid = document(UUID.randomUUID());
        var empty = new ResultDocument("1.0", valid.summary(), List.of(), valid.mindmap());
        assertThatIllegalArgumentException().isThrownBy(() -> store.saveDraft(claim, empty, NOW));
        var original = valid.sections().getFirst();
        var invalid = new ResultDocument.Section("not-a-uuid", original.title(), 0, 2_000, original.summary(),
                original.subtopics(), original.keywords(), original.detail());
        assertThatIllegalArgumentException().isThrownBy(() -> store.saveDraft(claim,
                new ResultDocument("1.0", valid.summary(), List.of(invalid), valid.mindmap()), NOW));
        assertThat(results.count()).isZero();
        assertThat(sections.count()).isZero();
        assertThat(jobs.findById(claim.jobId()).orElseThrow().getAnalysisId()).isNull();
    }

    private void assertUnfinished(JobQueue.Claim claim, UUID analysisId) {
        assertThat(results.findById(analysisId).orElseThrow().isChatReady()).isFalse();
        assertThat(records.count()).isZero();
        assertThat(jobs.findById(claim.jobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.PROCESSING);
    }

    private int chunkCount() { return jdbc.queryForObject("select count(*) from analysis_chunks", Integer.class); }

    private JobQueue.Claim fixture(Long duration) {
        tx.executeWithoutResult(status -> {
            String videoId = UUID.randomUUID().toString().substring(0, 11);
            var video = new Video(videoId);
            if (duration != null) video.updateMetadata(null, null, null, null, duration, null);
            em.persist(video);
            var batch = new AnalysisBatch(OWNER, null, NOW); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), videoId, NOW.plusSeconds(600), NOW); em.persist(item); em.flush();
            var upload = CaptionUpload.issued(OWNER, item.getId(), videoId, "test-bucket", UUID.randomUUID() + ".json",
                    "application/json", 100, NOW.plusSeconds(1));
            upload.consume(7); em.persist(upload); em.flush();
            var job = AnalysisJob.queued(OWNER, batch.getId(), item.getId(), videoId, upload.getId(), 7, null, NOW);
            em.persist(job); em.flush(); item.submit(job.getId(), NOW);
        });
        return queue.claim(NOW, LEASE).orElseThrow();
    }

    private JobQueue.Claim retry(JobQueue.Claim previous) {
        tx.executeWithoutResult(status -> {
            var batch = new AnalysisBatch(OWNER, null, NOW.plusSeconds(2)); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), previous.videoId(), NOW.plusSeconds(600), NOW.plusSeconds(2));
            em.persist(item); em.flush();
            var job = AnalysisJob.queued(OWNER, batch.getId(), item.getId(), previous.videoId(), previous.uploadId(), 7, previous.jobId(), NOW.plusSeconds(2));
            job.reuseProgress(jobs.findById(previous.jobId()).orElseThrow());
            em.persist(job); em.flush(); item.submit(job.getId(), NOW.plusSeconds(2));
        });
        return queue.claim(NOW.plusSeconds(2), LEASE).orElseThrow();
    }

    private static ResultDocument document(UUID sectionId) {
        var detail = new ResultDocument.Detail("핵심", "설명", List.of(), List.of(), List.of(), List.of());
        return new ResultDocument("1.0", new ResultDocument.Summary("한 줄", List.of("요점"), List.of(), List.of(), List.of(), List.of(sectionId.toString())),
                List.of(new ResultDocument.Section(sectionId.toString(), "주제", 0, 2_000, "요약", List.of(), List.of(), detail)),
                new ResultDocument.Mindmap("영상", List.of(sectionId.toString())));
    }

    private static Chunk chunk(WorkerResultStore.Draft draft, UUID id, String text) {
        return new Chunk(id, List.of(UUID.fromString(draft.document().sections().getFirst().sectionId())),
                List.of("s000001", "s000002"), text, 0, 2_000);
    }
}
