package com.gkk.itip.chat.conversation.dto;
import java.time.Instant;
import java.util.UUID;
public record ConversationCreated(UUID conversationId, ChatScope scope, String scopeLabel, int recordCount,
                                  Instant createdAt, UUID activeAssistantMessageId) {}
