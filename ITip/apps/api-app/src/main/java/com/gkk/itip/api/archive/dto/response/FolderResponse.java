package com.gkk.itip.api.archive.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FolderResponse(UUID folderId, String name, long recordCount) {
    public record Created(UUID folderId, String name, Instant createdAt) {}
    public record Item(UUID folderId, String name, Instant createdAt, long recordCount) {}
    public record Page(List<Item> items, String nextCursor, boolean hasNext) {}
    public record RecordsAdded(List<UUID> addedRecordIds, List<UUID> alreadyIncludedRecordIds) {}
}
