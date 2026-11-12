package com.gkk.itip.api.analysis.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateBatchRequest(
        @NotEmpty List<@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{11}") String> videoIds,
        @Size(max = 128) String playlistId) {}
