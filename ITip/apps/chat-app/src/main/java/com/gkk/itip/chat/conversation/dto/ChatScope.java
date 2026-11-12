package com.gkk.itip.chat.conversation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record ChatScope(@NotNull Type type, UUID folderId, @Size(max = 50) List<@NotNull UUID> keywordIds,
                        @Size(max = 200) String q,
                        @JsonInclude(JsonInclude.Include.NON_NULL) UUID recordId,
                        @JsonInclude(JsonInclude.Include.NON_NULL) UUID focusSectionId) {
    public enum Type { ALL, FILTERED, RECORD }
    public ChatScope {
        keywordIds = keywordIds == null ? List.of() : keywordIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        q = q == null ? null : q.strip();
        if (q != null && q.isEmpty()) q = null;
        boolean hasFilters = folderId != null || !keywordIds.isEmpty() || q != null;
        if (type == Type.ALL && hasFilters)
            throw new IllegalArgumentException("ALL scope cannot have filters");
        if (type == Type.RECORD && (recordId == null || hasFilters))
            throw new IllegalArgumentException("RECORD scope requires a record and cannot have filters");
        if (type != Type.RECORD && (recordId != null || focusSectionId != null))
            throw new IllegalArgumentException("record and section require RECORD scope");
    }
}
