package com.gkk.itip.api.analysis.dto.response;

import com.gkk.itip.domain.analysis.AnalysisStage;
import com.gkk.itip.domain.analysis.ItemStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BatchStatusResponse(UUID batchId, String status, Counts counts, List<Item> items,
                                  Long nextPollAfterMs, PlaylistInfo playlist) {
    public record Counts(int total, int awaitingUpload, int queued, int processing, int retryWait, int completed, int failed) {}
    public record Item(UUID itemId, String videoId, ItemStatus status, UUID jobId, UUID analysisId, UUID recordId,
                       Failure error, VideoInfo video, AnalysisStage stage, Instant uploadDeadlineAt) {}
    public record Failure(String code, String message, boolean retryable) {}
    public record VideoInfo(String videoId, String title, String channelName, String channelImageUrl,
                            String thumbnailUrl, Instant publishedAt, Long durationMs) {}
    public record PlaylistInfo(String playlistId, String title, String channelName, String thumbnailUrl, Integer totalItemCount) {}
}
