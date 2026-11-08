package com.gkk.itip.domain.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisJobTest {
    private final Instant now = Instant.parse("2026-11-08T00:00:00Z");
    private final Duration lease = Duration.ofMinutes(5);

    @Test
    void aReadyJobCanBeClaimedOnlyOnceBeforeLeaseExpiry() {
        AnalysisJob job = queued();
        UUID token = job.claim(now, lease);

        assertEquals(JobStatus.PROCESSING, job.getStatus());
        assertEquals(1, job.getAttempt());
        assertEquals(AnalysisStage.VALIDATING, job.getStage());
        assertTrue(job.isLeaseOwned(token, now));
        assertFalse(job.isReadyForClaim(now.plusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> job.claim(now.plusSeconds(1), lease));
    }

    @Test
    void aReclaimedJobRejectsTheOldWorkerToken() {
        AnalysisJob job = queued();
        UUID oldToken = job.claim(now, lease);
        UUID newToken = job.claim(now.plus(lease), lease);

        assertNotEquals(oldToken, newToken);
        assertEquals(2, job.getAttempt());
        assertThrows(IllegalStateException.class,
                () -> job.changeStage(oldToken, AnalysisStage.SUMMARIZING, now.plus(lease)));
        assertThrows(IllegalStateException.class,
                () -> job.fail(oldToken, "AI_TIMEOUT", "분석 응답 지연", true,
                        now.plus(lease), Duration.ofSeconds(10)));
        assertTrue(job.isLeaseOwned(newToken, now.plus(lease)));
    }

    @Test
    void retryableFailureWaitsUntilItsNextAttemptTime() {
        AnalysisJob job = queued();
        UUID token = job.claim(now, lease);
        job.fail(token, "AI_TIMEOUT", "분석 응답 지연", true, now, Duration.ofSeconds(10));

        assertEquals(JobStatus.RETRY_WAIT, job.getStatus());
        assertEquals("AI_TIMEOUT", job.getErrorCode());
        assertTrue(job.getRetryable());
        assertFalse(job.isReadyForClaim(now.plusSeconds(9)));
        assertThrows(IllegalStateException.class, () -> job.claim(now.plusSeconds(9), lease));
        assertTrue(job.isReadyForClaim(now.plusSeconds(10)));
        job.claim(now.plusSeconds(10), lease);
        assertNull(job.getErrorCode());
        assertEquals(2, job.getAttempt());
    }

    @Test
    void automaticExecutionStopsAfterThreeAttempts() {
        AnalysisJob job = queued();
        for (int attempt = 1; attempt <= 3; attempt++) {
            UUID token = job.claim(now, lease);
            job.fail(token, "AI_TIMEOUT", "분석 응답 지연", true, now, Duration.ZERO);
        }

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(3, job.getAttempt());
        assertTrue(job.getRetryable());
        assertFalse(job.isReadyForClaim(now));
        assertThrows(IllegalStateException.class, () -> job.claim(now, lease));
    }

    @Test
    void aNonRetryableFailureDoesNotScheduleAnotherAttempt() {
        AnalysisJob job = queued();
        UUID token = job.claim(now, lease);
        job.fail(token, "INVALID_CAPTIONS", "자막 형식 확인 필요", false, now, Duration.ZERO);

        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals(1, job.getAttempt());
        assertFalse(job.getRetryable());
        assertNull(job.getNextAttemptAt());
        assertNull(job.getRunToken());
    }

    @Test
    void completionRequiresFinishingStageAndLiveToken() {
        AnalysisJob job = queued();
        UUID token = job.claim(now, lease);
        UUID analysisId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> job.complete(token, analysisId, recordId, now));
        job.changeStage(token, AnalysisStage.SUMMARIZING, now);
        job.changeStage(token, AnalysisStage.INDEXING, now);
        job.changeStage(token, AnalysisStage.FINISHING, now);
        assertThrows(IllegalStateException.class, () -> job.complete(token, analysisId, recordId, now.plus(lease)));
        job.complete(token, analysisId, recordId, now);

        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertEquals(analysisId, job.getAnalysisId());
        assertEquals(recordId, job.getRecordId());
        assertNull(job.getRunToken());
        assertNull(job.getNextAttemptAt());
    }

    @Test
    void successfulStagesAreNotRewoundOnAutomaticRetry() {
        AnalysisJob job = queued();
        UUID token = job.claim(now, lease);
        job.changeStage(token, AnalysisStage.INDEXING, now);
        assertThrows(IllegalStateException.class,
                () -> job.changeStage(token, AnalysisStage.SUMMARIZING, now));
        job.fail(token, "INDEX_TIMEOUT", "검색 데이터 저장 지연", true, now, Duration.ZERO);
        UUID retryToken = job.claim(now, lease);
        assertEquals(AnalysisStage.INDEXING, job.getStage());
        assertTrue(job.isLeaseOwned(retryToken, now));
    }

    @Test
    void claimRejectsNonPositiveLeaseWithoutChangingJob() {
        AnalysisJob job = queued();
        assertThrows(IllegalArgumentException.class, () -> job.claim(now, Duration.ZERO));
        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertEquals(0, job.getAttempt());
    }

    private AnalysisJob queued() {
        return AnalysisJob.queued("demo", UUID.randomUUID(), UUID.randomUUID(), "dQw4w9WgXcQ",
                UUID.randomUUID(), 123L, null, now);
    }

    @Test
    void attachedDraftRequiresLiveLeaseAndIsRetainedOnAutomaticRetry() {
        AnalysisJob job = queued(); UUID token = job.claim(now, lease); UUID draft = UUID.randomUUID();
        job.changeStage(token, AnalysisStage.SUMMARIZING, now);
        assertThrows(IllegalStateException.class, () -> job.attachDraft(UUID.randomUUID(), draft, now));
        assertNull(job.getAnalysisId());
        job.attachDraft(token, draft, now);
        assertEquals(draft, job.getAnalysisId()); assertEquals(AnalysisStage.INDEXING, job.getStage());
        assertThrows(IllegalStateException.class, () -> job.attachDraft(token, UUID.randomUUID(), now));
        job.fail(token, "INDEX_TIMEOUT", "검색 저장 실패", true, now, Duration.ZERO);
        job.claim(now, lease);
        assertEquals(draft, job.getAnalysisId()); assertEquals(AnalysisStage.INDEXING, job.getStage());
    }

    @Test
    void manualRetryCopiesDraftAndStartsIndexingWithFreshAttempts() {
        AnalysisJob previous = queued(); UUID token = previous.claim(now, lease); UUID draft = UUID.randomUUID();
        previous.attachDraft(token, draft, now); previous.changeStage(token, AnalysisStage.FINISHING, now);
        previous.fail(token, "FINISH_FAILED", "완료 저장 실패", false, now, Duration.ZERO);
        AnalysisJob next = AnalysisJob.queued(previous.getOwnerId(), UUID.randomUUID(), UUID.randomUUID(), previous.getVideoId(),
                previous.getUploadId(), previous.getGeneration(), previous.getId(), now);
        next.reuseProgress(previous);
        assertEquals(0, next.getAttempt()); assertEquals(3, next.getMaxAttempts()); assertEquals(draft, next.getAnalysisId());
        next.claim(now, lease); assertEquals(AnalysisStage.INDEXING, next.getStage());
        assertThrows(IllegalStateException.class, () -> next.reuseProgress(previous));
    }
}
