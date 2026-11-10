package com.gkk.itip.api.archive.dto.request;

import jakarta.validation.constraints.NotBlank;

public record FolderNameRequest(@NotBlank String name) {}
