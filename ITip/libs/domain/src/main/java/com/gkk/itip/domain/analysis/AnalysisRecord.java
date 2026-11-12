package com.gkk.itip.domain.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "analysis_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisRecord {
    @Id
    private UUID id;
    @Column(nullable = false, length = 64)
    private String ownerId;
    @Column(nullable = false, length = 32)
    private String videoId;
    @Column(nullable = false)
    private UUID analysisId;
    @Column(nullable = false)
    private Instant analyzedAt;
    @Column(length = 16)
    private String readingTab;
    private UUID readingSectionId;
    private Instant lastViewedAt;

    public void saveReadingPosition(String tab, UUID sectionId, Instant viewedAt) {
        readingTab = Objects.requireNonNull(tab);
        readingSectionId = sectionId;
        lastViewedAt = Objects.requireNonNull(viewedAt);
    }

    public static AnalysisRecord fromCompleted(AnalysisResult result) {
        Objects.requireNonNull(result);
        if (!result.isChatReady() || result.getCompletedAt() == null) {
            throw new IllegalStateException("personal record requires a completed analysis result");
        }
        AnalysisRecord record = new AnalysisRecord();
        record.id = UUID.randomUUID();
        record.ownerId = result.getOwnerId();
        record.videoId = result.getVideoId();
        record.analysisId = result.getId();
        record.analyzedAt = result.getCompletedAt();
        return record;
    }
}
