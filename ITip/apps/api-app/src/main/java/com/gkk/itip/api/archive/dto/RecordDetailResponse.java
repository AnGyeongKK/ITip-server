package com.gkk.itip.api.archive.dto;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse;
import com.gkk.itip.api.archive.dto.ReadingPositionResponse.ReadingPosition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecordDetailResponse(RecordInfo record, AnalysisResultResponse analysis,
                                   List<FolderInfo> folders, ReadingPosition readingPosition) {
    public record RecordInfo(UUID recordId, UUID analysisId, String videoId, Instant analyzedAt) {}
    public record FolderInfo(UUID folderId, String name) {}
}
