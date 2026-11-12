package com.gkk.itip.domain.chat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "chat_message_sources") @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessageSource {
    @Id private UUID id;
    @Column(nullable = false) private UUID messageId;
    @Column(nullable = false, length = 64) private String sourceId;
    @Column(nullable = false) private UUID recordId;
    @Column(nullable = false) private UUID analysisId;
    @Column(nullable = false) private UUID sectionId;
    @Column(nullable = false, length = 32) private String videoId;
    @Column(length = 500) private String title;
    @Column(nullable = false) private long startMs;
    @Column(nullable = false) private long endMs;
    @Column(nullable = false) private Instant analyzedAt;

    public static ChatMessageSource snapshot(UUID message, String source, UUID record, UUID analysis, UUID section,
            String video, String title, long start, long end, Instant analyzedAt) {
        var s = new ChatMessageSource();
        s.id=UUID.randomUUID(); s.messageId=message; s.sourceId=source; s.recordId=record; s.analysisId=analysis;
        s.sectionId=section; s.videoId=video; s.title=title; s.startMs=start; s.endMs=end; s.analyzedAt=analyzedAt;
        return s;
    }
}
