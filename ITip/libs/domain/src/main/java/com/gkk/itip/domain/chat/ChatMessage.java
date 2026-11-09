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
}
