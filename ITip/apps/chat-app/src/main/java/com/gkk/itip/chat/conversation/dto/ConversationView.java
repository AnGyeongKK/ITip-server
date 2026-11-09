package com.gkk.itip.chat.conversation.dto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class ConversationView {
    private ConversationView() {}
    public record Brief(UUID conversationId, String title, String lastQuestion, String scopeLabel,
                        UUID activeAssistantMessageId, Instant updatedAt) {}
    public record Page(List<Brief> items, String nextCursor, boolean hasNext) {}
    public record Detail(UUID conversationId, ChatScope scope, String scopeLabel, int recordCount, String scopeStatus,
                         UUID activeAssistantMessageId, Instant createdAt, Instant updatedAt) {}
}
