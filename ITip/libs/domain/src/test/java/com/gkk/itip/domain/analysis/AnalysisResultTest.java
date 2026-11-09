package com.gkk.itip.domain.analysis;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisResultTest {
    private final Instant now = Instant.parse("2026-11-08T00:00:00Z");

    @Test
    void draftIsNotChatReadyUntilCompletionIsConfirmed() {
        AnalysisResult result = draft("{\"schemaVersion\":\"1.0\"}");
        assertFalse(result.isChatReady());
        assertNull(result.getCompletedAt());

        result.markReady(now.plusSeconds(1));

        assertTrue(result.isChatReady());
        assertEquals(now.plusSeconds(1), result.getCompletedAt());
        assertThrows(IllegalStateException.class, () -> result.markReady(now.plusSeconds(2)));
    }

    @Test
    void draftRejectsMissingPayloadAndCompletionBeforeCreation() {
        assertThrows(IllegalArgumentException.class, () -> draft("  "));
        assertThrows(NullPointerException.class, () -> draft(null));
        AnalysisResult result = draft("{}");
        assertThrows(IllegalArgumentException.class, () -> result.markReady(now.minusSeconds(1)));
        assertFalse(result.isChatReady());
    }

    @Test
    void personalRecordCanOnlyBeCreatedFromCompletedResult() {
        AnalysisResult result = draft("{}");
        assertThrows(IllegalStateException.class, () -> AnalysisRecord.fromCompleted(result));
        result.markReady(now.plusSeconds(2));

        AnalysisRecord record = AnalysisRecord.fromCompleted(result);

        assertNotNull(record.getId());
        assertEquals(result.getId(), record.getAnalysisId());
        assertEquals(result.getOwnerId(), record.getOwnerId());
        assertEquals(result.getVideoId(), record.getVideoId());
        assertEquals(result.getCompletedAt(), record.getAnalyzedAt());
    }

    @Test
    void sectionKeepsServerIdAndRejectsInvalidTimeRangeOrMissingDetail() {
        UUID sectionId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        AnalysisSection section = new AnalysisSection(sectionId, analysisId, "검색 흐름", 0L, 1000L, "{}");
        assertEquals(sectionId, section.getId());
        assertEquals(analysisId, section.getAnalysisId());
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisSection(sectionId, analysisId, "검색 흐름", -1L, 1000L, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisSection(sectionId, analysisId, "검색 흐름", 1000L, 1000L, "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisSection(sectionId, analysisId, "검색 흐름", 0L, 1000L, " "));
    }

    private AnalysisResult draft(String json) {
        return AnalysisResult.draft(UUID.randomUUID(), "demo", "dQw4w9WgXcQ", UUID.randomUUID(), json, now);
    }
}
