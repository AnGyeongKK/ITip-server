package com.gkk.itip.api.archive.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FolderNameRequest(@NotBlank @Size(max = 100) String name) {}
