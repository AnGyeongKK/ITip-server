package com.gkk.itip.api.analysis.dto.response;

import com.gkk.itip.domain.analysis.JobStatus;
import java.util.UUID;

public record JobRetriedResponse(UUID jobId, UUID previousJobId, UUID batchId, JobStatus status, String statusUrl) {}
