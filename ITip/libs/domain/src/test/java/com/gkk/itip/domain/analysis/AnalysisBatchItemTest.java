package com.gkk.itip.domain.analysis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisBatchItemTest {
    private final Instant now = Instant.parse("2026-11-08T00:00:00Z");

    @Test
    void collectionFailureCannotCancelAnAlreadySubmittedItem() {
        AnalysisBatchItem item = AnalysisBatchItem.collectCaptions(UUID.randomUUID(), "dQw4w9WgXcQ",
                now.plusSeconds(600), now);
        UUID jobId = UUID.randomUUID();
        item.submit(jobId, now);

        assertThrows(IllegalStateException.class,
                () -> item.failCollection("UPLOAD_FAILED", "업로드 실패", true, now));
        assertEquals(ItemStatus.QUEUED, item.getStatus());
        assertEquals(jobId, item.getJobId());
    }

    @Test
    void awaitingUploadCanExpireWithoutCreatingAJob() {
        AnalysisBatchItem item = AnalysisBatchItem.collectCaptions(UUID.randomUUID(), "dQw4w9WgXcQ",
                now.plusSeconds(600), now);
        assertFalse(item.expireAwaitingUpload(now.plusSeconds(599)));
        assertTrue(item.expireAwaitingUpload(now.plusSeconds(600)));
        assertEquals(ItemStatus.FAILED, item.getStatus());
        assertNull(item.getJobId());
        assertEquals("CAPTION_UPLOAD_EXPIRED", item.getErrorCode());
    }

    @Test
    void completedResultReferenceIsSeparateFromJobReference() {
        UUID analysisId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        AnalysisBatchItem item = AnalysisBatchItem.openResult(UUID.randomUUID(), "dQw4w9WgXcQ",
                analysisId, recordId, now);
        assertEquals(ItemAction.OPEN_RESULT, item.getAction());
        assertEquals(ItemStatus.COMPLETED, item.getStatus());
        assertEquals(analysisId, item.getAnalysisId());
        assertEquals(recordId, item.getRecordId());
        assertNull(item.getJobId());
        assertNull(item.getUploadDeadlineAt());
    }
}
