package com.gkk.itip.api.archive.dto.response;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse.VideoInfo;
import com.gkk.itip.domain.analysis.result.ResultDocument.Keyword;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ArchiveHomeResponse(List<AnalysisCard> recentAnalyses, ContinueReading continueReading) {
    public record AnalysisCard(UUID recordId, UUID analysisId, VideoInfo video, String oneLine,
                               List<Keyword> keywords, Instant analyzedAt, Instant lastViewedAt) {}
    public record ContinueReading(UUID recordId, UUID analysisId, String videoTitle, String tab,
                                  UUID sectionId, String sectionTitle, long startMs, long endMs,
                                  Long positionMs, Instant lastViewedAt, String sectionSummary,
                                  int practicalTipCount) {}
}
