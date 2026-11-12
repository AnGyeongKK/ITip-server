package com.gkk.itip.api.archive.dto;

import java.time.Instant;
import java.util.UUID;

public record ReadingPositionResponse(ReadingPosition readingPosition) {
    public record ReadingPosition(String tab, UUID sectionId, Long positionMs, Instant lastViewedAt) {}
}
