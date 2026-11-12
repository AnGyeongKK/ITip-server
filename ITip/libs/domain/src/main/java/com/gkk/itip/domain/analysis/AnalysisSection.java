package com.gkk.itip.domain.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "analysis_sections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisSection {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID analysisId;
    @Column(nullable = false, length = 500)
    private String title;
    @Column(nullable = false)
    private long startMs;
    @Column(nullable = false)
    private long endMs;
    @Column(nullable = false, columnDefinition = "text")
    private String detailJson;

    public AnalysisSection(UUID id, UUID analysisId, String title, long startMs, long endMs, String validatedDetailJson) {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("section time range must be positive and ordered");
        }
        if (Objects.requireNonNull(title).isBlank() || Objects.requireNonNull(validatedDetailJson).isBlank()) {
            throw new IllegalArgumentException("section title and detail must not be blank");
        }
        this.id = Objects.requireNonNull(id);
        this.analysisId = Objects.requireNonNull(analysisId);
        this.title = title;
        this.startMs = startMs;
        this.endMs = endMs;
        this.detailJson = validatedDetailJson;
    }
}
