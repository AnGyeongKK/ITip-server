package com.gkk.itip.api.analysis.dto.response;

import com.gkk.itip.domain.analysis.ItemAction;
import com.gkk.itip.domain.analysis.ItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BatchCreatedResponse(UUID batchId, String status, List<Item> items, SelectionSummary selectionSummary) {
    public record Item(UUID itemId, String videoId, ItemAction action, ItemStatus status,
                       UUID jobId, UUID analysisId, UUID recordId, Instant uploadDeadlineAt) {}
    public record SelectionSummary(int uniqueVideoCount, int newAnalysisCount, int existingResultCount, int existingJobCount) {}
}
