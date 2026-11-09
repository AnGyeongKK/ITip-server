package com.gkk.itip.domain.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "analysis_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisJob {
    public static final int MAX_ATTEMPTS = 3;

    @Id
    private UUID id;
    @Column(nullable = false, length = 64)
    private String ownerId;
    @Column(nullable = false)
    private UUID batchId;
    @Column(nullable = false)
    private UUID batchItemId;
    @Column(nullable = false, length = 32)
    private String videoId;
    @Column(nullable = false)
    private UUID uploadId;
    @Column(nullable = false)
    private long generation;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AnalysisStage stage;
    @Column(nullable = false)
    private int attempt;
    @Column(nullable = false)
    private int maxAttempts;
    private Instant nextAttemptAt;
    private UUID runToken;
    private Instant leaseUntil;
    private UUID previousJobId;
    private UUID analysisId;
    private UUID recordId;
    @Column(length = 100)
    private String errorCode;
    @Column(length = 500)
    private String errorMessage;
    private Boolean retryable;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public static AnalysisJob queued(String ownerId, UUID batchId, UUID batchItemId, String videoId,
                                     UUID uploadId, long generation, UUID previousJobId, Instant now) {
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        AnalysisJob job = new AnalysisJob();
        job.id = UUID.randomUUID();
        job.ownerId = Objects.requireNonNull(ownerId);
        job.batchId = Objects.requireNonNull(batchId);
        job.batchItemId = Objects.requireNonNull(batchItemId);
        job.videoId = Objects.requireNonNull(videoId);
        job.uploadId = Objects.requireNonNull(uploadId);
        job.generation = generation;
        job.status = JobStatus.QUEUED;
        job.maxAttempts = MAX_ATTEMPTS;
        job.nextAttemptAt = Objects.requireNonNull(now);
        job.previousJobId = previousJobId;
        job.createdAt = now;
        job.updatedAt = now;
        return job;
    }

    public boolean isReadyForClaim(Instant now) {
        if (attempt >= maxAttempts) {
            return false;
        }
        return switch (status) {
            case QUEUED, RETRY_WAIT -> nextAttemptAt != null && !now.isBefore(nextAttemptAt);
            case PROCESSING -> leaseUntil != null && !now.isBefore(leaseUntil);
            case COMPLETED, FAILED -> false;
        };
    }

    public UUID claim(Instant now, Duration leaseDuration) {
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        if (!isReadyForClaim(now)) {
            throw new IllegalStateException("job is not ready for claim");
        }
        this.status = JobStatus.PROCESSING;
        this.attempt++;
        this.runToken = UUID.randomUUID();
        this.leaseUntil = now.plus(leaseDuration);
        this.nextAttemptAt = null;
        if (this.stage == null) {
            this.stage = AnalysisStage.VALIDATING;
        }
        clearError();
        this.updatedAt = now;
        return runToken;
    }

    public boolean isLeaseOwned(UUID token, Instant now) {
        return status == JobStatus.PROCESSING && token != null && token.equals(runToken)
                && leaseUntil != null && now.isBefore(leaseUntil);
    }

    public void renewLease(UUID token, Instant now, Duration leaseDuration) {
        requireLease(token, now);
        if (leaseDuration.isNegative() || leaseDuration.isZero())
            throw new IllegalArgumentException("lease duration must be positive");
        this.leaseUntil = now.plus(leaseDuration);
        this.updatedAt = now;
    }

    public void changeStage(UUID token, AnalysisStage nextStage, Instant now) {
        requireLease(token, now);
        if (Objects.requireNonNull(nextStage).ordinal() < stage.ordinal()) {
            throw new IllegalStateException("successful stages cannot be rewound");
        }
        this.stage = nextStage;
        this.updatedAt = now;
    }

    public void attachDraft(UUID token, UUID analysisId, Instant now) {
        requireLease(token, now);
        UUID draftId = Objects.requireNonNull(analysisId);
        if (this.analysisId != null && !this.analysisId.equals(draftId))
            throw new IllegalStateException("a saved draft cannot be replaced");
        this.analysisId = draftId;
        if (stage.ordinal() < AnalysisStage.INDEXING.ordinal()) this.stage = AnalysisStage.INDEXING;
        this.updatedAt = now;
    }

    public void reuseProgress(AnalysisJob previous) {
        if (status != JobStatus.QUEUED || attempt != 0 || analysisId != null || stage != null
                || !Objects.equals(previousJobId, previous.id) || previous.status != JobStatus.FAILED
                || !ownerId.equals(previous.ownerId) || !videoId.equals(previous.videoId)
                || !uploadId.equals(previous.uploadId) || generation != previous.generation)
            throw new IllegalStateException("progress can only be copied to a fresh retry of the same source");
        if (previous.analysisId != null) {
            this.analysisId = previous.analysisId;
            this.stage = AnalysisStage.INDEXING;
        }
    }

    public void fail(UUID token, String code, String message, boolean retryable, Instant now, Duration retryDelay) {
        requireLease(token, now);
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retry delay must not be negative");
        }
        String failureCode = Objects.requireNonNull(code);
        String failureMessage = Objects.requireNonNull(message);
        boolean scheduleRetry = retryable && attempt < maxAttempts;
        this.status = scheduleRetry ? JobStatus.RETRY_WAIT : JobStatus.FAILED;
        this.nextAttemptAt = scheduleRetry ? now.plus(retryDelay) : null;
        this.errorCode = failureCode;
        this.errorMessage = failureMessage;
        this.retryable = retryable;
        clearLease();
        this.updatedAt = now;
    }

    public void complete(UUID token, UUID analysisId, UUID recordId, Instant now) {
        requireLease(token, now);
        if (stage != AnalysisStage.FINISHING) {
            throw new IllegalStateException("all analysis stages must finish before completion");
        }
        UUID completedAnalysisId = Objects.requireNonNull(analysisId);
        UUID completedRecordId = Objects.requireNonNull(recordId);
        this.analysisId = completedAnalysisId;
        this.recordId = completedRecordId;
        this.status = JobStatus.COMPLETED;
        this.nextAttemptAt = null;
        clearError();
        clearLease();
        this.updatedAt = now;
    }

    private void requireLease(UUID token, Instant now) {
        if (!isLeaseOwned(token, now)) {
            throw new IllegalStateException("worker no longer owns this job lease");
        }
    }

    private void clearLease() {
        this.runToken = null;
        this.leaseUntil = null;
    }

    private void clearError() {
        this.errorCode = null;
        this.errorMessage = null;
        this.retryable = null;
    }
}
