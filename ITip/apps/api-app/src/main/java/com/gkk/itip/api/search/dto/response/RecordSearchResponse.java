package com.gkk.itip.api.search.dto.response;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse.VideoInfo;
import com.gkk.itip.domain.analysis.result.ResultDocument.Keyword;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecordSearchResponse(List<Item> items, String nextCursor, boolean hasNext, long matchedCount,
                                   Filters filters, SelectionContext selectionContext) {
    public record Item(UUID recordId, UUID analysisId, VideoInfo video, String oneLine, List<Keyword> keywords,
                       Instant analyzedAt, List<Match> matches, FolderMembership folderMembership) {}

    public record Match(Field field, String text, String sectionId) {
        public enum Field { TITLE, ONE_LINE, KEYWORD, DETAIL, TECHNOLOGY, TOOL }
    }

    public record Filters(String q, String folderId, List<String> keywordIds, String playlistGroupId) {}
    public record SelectionContext(String targetFolderId) {}
    public record FolderMembership(String folderId, boolean included) {}
}
