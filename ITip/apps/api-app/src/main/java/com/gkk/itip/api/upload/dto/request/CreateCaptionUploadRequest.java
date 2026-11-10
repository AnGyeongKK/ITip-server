package com.gkk.itip.api.upload.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record CreateCaptionUploadRequest(
        @NotNull UUID batchId,
        @NotNull UUID batchItemId,
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{11}") String videoId,
        @NotNull @Pattern(regexp = "application/json") String contentType,
        @NotNull @Positive Long sizeBytes
) {}
