package com.gkk.itip.domain.chat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "chat_messages") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {
    @Id private UUID id;
    @Column(nullable = false) private UUID conversationId;
    @Column(nullable = false) private long position;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private MessageRole role;
    @Column(nullable = false) private UUID clientRequestId;
    @Column(nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, columnDefinition = "text") private String content;
    private UUID replyToMessageId;
    private UUID retryOfMessageId;
    @Enumerated(EnumType.STRING) @Column(length = 32) private MessageStatus status;
    @Enumerated(EnumType.STRING) @Column(length = 32) private FinishReason finishReason;
    private Integer scopeRecordCount;
    private Integer usedRecordCount;
    @Column(length = 100) private String errorCode;
    @Column(length = 500) private String errorMessage;
    private Boolean retryable;
    private UUID generationToken;
    private Instant leaseUntil;
    @Column(nullable = false) private Instant createdAt;
    private Instant completedAt;

    public static ChatMessage question(UUID room, long position, UUID key, String hash, String text, Instant now) {
        var m = new ChatMessage();
        m.id = UUID.randomUUID(); m.conversationId = room; m.position = position;
        m.clientRequestId = key; m.requestHash = hash; m.content = text; m.createdAt = now; m.role = MessageRole.USER;
        return m;
    }
    public static ChatMessage answer(ChatMessage question, int scopeCount, UUID retryOf, UUID token, Instant lease) {
        var m = question(question.conversationId, question.position + 1, question.clientRequestId, question.requestHash, "", question.createdAt);
        m.role = MessageRole.ASSISTANT; m.replyToMessageId = question.id; m.retryOfMessageId = retryOf;
        m.status = MessageStatus.GENERATING; m.scopeRecordCount = scopeCount; m.usedRecordCount = 0;
        m.retryable = false; m.generationToken = token; m.leaseUntil = lease;
        return m;
    }
    public boolean ownedGeneration(UUID token, Instant now) {
        return status != null && status.active() && token.equals(generationToken) && leaseUntil.isAfter(now);
    }
    public void checkpoint(String text) { requireActive(); content = text; }
    public void fail(String text, String code, boolean mayRetry) {
        requireActive(); content = text; status = text.isEmpty() ? MessageStatus.FAILED : MessageStatus.INCOMPLETE;
        errorCode = code; errorMessage = "답변 생성이 중단됐어요."; retryable = mayRetry;
        generationToken = null; leaseUntil = null;
    }
    public void complete(String text, FinishReason reason, int used, Instant now) {
        requireActive();
        if (text.isBlank() || reason == null || used < 0 || used > scopeRecordCount) throw new IllegalArgumentException("invalid completion");
        content = text; status = MessageStatus.COMPLETED; finishReason = reason; usedRecordCount = used;
        completedAt = now.isBefore(createdAt) ? createdAt : now; retryable = false; generationToken = null; leaseUntil = null;
    }
    private void requireActive() {
        if (status == null || !status.active()) throw new IllegalStateException("generation is not active");
    }
}
