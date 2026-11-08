package com.gkk.itip.api.analysis.dto.response;

import com.gkk.itip.domain.analysis.AnalysisStage;
import com.gkk.itip.domain.analysis.JobStatus;
import java.time.Instant;
import java.util.UUID;

public record JobStatusResponse(UUID jobId, UUID batchId, String videoId, JobStatus status, AnalysisStage stage,
                                int attempt, int maxAttempts, UUID analysisId, UUID recordId,
                                BatchStatusResponse.Failure error, Long nextPollAfterMs, Instant updatedAt,
                                BatchStatusResponse.VideoInfo video) {}
