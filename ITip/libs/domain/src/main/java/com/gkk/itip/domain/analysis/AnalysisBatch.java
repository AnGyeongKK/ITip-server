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
@Table(name = "analysis_batches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisBatch {
    @Id
    private UUID id;
    @Column(nullable = false, length = 64)
    private String ownerId;
    @Column(length = 128)
    private String playlistId;
    @Column(nullable = false)
    private Instant createdAt;

    public AnalysisBatch(String ownerId, String playlistId, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = Objects.requireNonNull(ownerId);
        this.playlistId = playlistId;
        this.createdAt = Objects.requireNonNull(now);
    }
}
