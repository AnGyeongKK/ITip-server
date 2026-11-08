package com.gkk.itip.api.analysis.dto.response;

import com.gkk.itip.domain.analysis.JobStatus;
import java.util.UUID;

public record JobSubmittedResponse(UUID jobId, UUID batchId, UUID batchItemId, JobStatus status,
                                   String statusUrl, UUID analysisId, UUID recordId) {}
