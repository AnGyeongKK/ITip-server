package com.gkk.itip.api.archive.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record AddFolderRecordsRequest(@NotEmpty List<@NotNull UUID> recordIds) {}
