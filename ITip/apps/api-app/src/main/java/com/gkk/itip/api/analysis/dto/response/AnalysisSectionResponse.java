package com.gkk.itip.api.analysis.dto.response;

import com.gkk.itip.domain.analysis.result.ResultDocument;

import java.util.List;
import java.util.UUID;

public record AnalysisSectionResponse(UUID analysisId, UUID recordId, String sectionId, String title,
                                      long startMs, long endMs, String summary, List<String> subtopics,
                                      List<ResultDocument.Keyword> keywords, String videoId, String originalUrl,
                                      ResultDocument.Detail detail, AnalysisResultResponse.VideoInfo video,
                                      String videoOneLine) {}
