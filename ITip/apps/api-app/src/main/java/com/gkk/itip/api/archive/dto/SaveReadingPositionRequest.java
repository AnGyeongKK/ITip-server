package com.gkk.itip.api.archive.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record SaveReadingPositionRequest(
        @NotNull @Pattern(regexp = "SUMMARY|MINDMAP|DETAIL") String tab,
        @JsonProperty(value = "sectionId", required = true) UUID sectionId,
        @Null Long positionMs) {}
