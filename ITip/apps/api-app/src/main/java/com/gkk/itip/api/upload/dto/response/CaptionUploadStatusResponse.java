package com.gkk.itip.api.upload.dto.response;

import com.gkk.itip.domain.upload.UploadStatus;

import java.time.Instant;
import java.util.UUID;

public record CaptionUploadStatusResponse(UUID uploadId, UUID batchItemId, UploadStatus status,
                                          Instant expiresAt, UUID jobId) {}
