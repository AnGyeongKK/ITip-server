package com.gkk.itip.api.archive.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlaylistGroupResponse(UUID groupId, String playlistId, String title, String originalUrl,
                                    long recordCount, Instant lastAnalyzedAt) {
    public record Page(List<PlaylistGroupResponse> items, String nextCursor, boolean hasNext) {}
}
