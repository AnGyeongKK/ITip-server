package com.gkk.itip.api.upload.dto.response;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CaptionUploadCreatedResponse(UUID uploadId, String method, String uploadUrl,
                                           Map<String, String> headers, Instant expiresAt, long maxBytes) {}
