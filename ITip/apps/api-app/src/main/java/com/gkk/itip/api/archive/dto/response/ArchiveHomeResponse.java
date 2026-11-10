package com.gkk.itip.api.archive.dto.response;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse.VideoInfo;
import com.gkk.itip.api.archive.dto.ReadingPositionResponse.ReadingPosition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ArchiveHomeResponse(List<AnalysisCard> recentAnalyses, ContinueReading continueReading) {
    public record AnalysisCard(UUID recordId, UUID analysisId, VideoInfo video, Instant analyzedAt) {}
    public record ContinueReading(AnalysisCard record, ReadingPosition readingPosition, String sectionTitle) {}
}
