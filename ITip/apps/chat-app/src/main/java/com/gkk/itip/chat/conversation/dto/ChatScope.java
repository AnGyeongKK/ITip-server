package com.gkk.itip.chat.conversation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record ChatScope(@NotNull Type type, UUID folderId, @Size(max = 50) List<@NotNull UUID> keywordIds,
                        @Size(max = 200) String q) {
    public enum Type { ALL, FILTERED }
    public ChatScope {
        keywordIds = keywordIds == null ? List.of() : keywordIds.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        q = q == null ? null : q.strip().replaceAll("\\s+", " ");
        if (q != null && q.isEmpty()) q = null;
        if (type == Type.ALL && (folderId != null || !keywordIds.isEmpty() || q != null))
            throw new IllegalArgumentException("ALL scope cannot have filters");
    }
}
