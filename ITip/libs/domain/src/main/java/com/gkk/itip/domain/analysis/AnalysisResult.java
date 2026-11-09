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
@Table(name = "analysis_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisResult {
    @Id
    private UUID id;
    @Column(nullable = false, length = 64)
    private String ownerId;
    @Column(nullable = false, length = 32)
    private String videoId;
    @Column(nullable = false, unique = true)
    private UUID sourceJobId;
    @Column(nullable = false, columnDefinition = "text")
    private String resultJson;
    @Column(nullable = false)
    private boolean chatReady;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant completedAt;

    public static AnalysisResult draft(UUID id, String ownerId, String videoId, UUID sourceJobId,
                                       String validatedResultJson, Instant now) {
        if (Objects.requireNonNull(validatedResultJson).isBlank()) {
            throw new IllegalArgumentException("validated result JSON must not be blank");
        }
        AnalysisResult result = new AnalysisResult();
        result.id = Objects.requireNonNull(id);
        result.ownerId = Objects.requireNonNull(ownerId);
        result.videoId = Objects.requireNonNull(videoId);
        result.sourceJobId = Objects.requireNonNull(sourceJobId);
        result.resultJson = validatedResultJson;
        result.createdAt = Objects.requireNonNull(now);
        return result;
    }

    public void markReady(Instant now) {
        if (chatReady) {
            throw new IllegalStateException("analysis result is already completed");
        }
        if (Objects.requireNonNull(now).isBefore(createdAt)) {
            throw new IllegalArgumentException("completion must not precede result creation");
        }
        this.chatReady = true;
        this.completedAt = now;
    }
}
