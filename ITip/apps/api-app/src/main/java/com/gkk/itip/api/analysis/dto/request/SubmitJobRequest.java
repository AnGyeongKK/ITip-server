package com.gkk.itip.api.analysis.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SubmitJobRequest(@NotNull UUID batchId, @NotNull UUID batchItemId, @NotNull UUID uploadId) {}
