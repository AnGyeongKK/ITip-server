package com.gkk.itip.domain.chat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "chat_conversations") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatConversation {
    @Id private UUID id;
    @Column(nullable = false, length = 64) private String ownerId;
    @Column(nullable = false, columnDefinition = "text") private String scopeJson;
    @Column(nullable = false, length = 500) private String scopeLabel;
    @Column(nullable = false, length = 160) private String title;
    @Column(columnDefinition = "text") private String lastQuestion;
    @Column(nullable = false) private long nextPosition;
    @Column(nullable = false) private int initialRecordCount;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    public static ChatConversation create(String owner, String scopeJson, String label, int count, Instant now) {
        var room = new ChatConversation();
        room.id = UUID.randomUUID(); room.ownerId = owner; room.scopeJson = scopeJson;
        room.scopeLabel = label; room.initialRecordCount = count; room.title = "새 대화";
        room.createdAt = now; room.updatedAt = now;
        return room;
    }
}
