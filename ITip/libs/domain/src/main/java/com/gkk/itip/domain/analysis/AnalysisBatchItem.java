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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "analysis_batch_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisBatchItem {
    @Id
    private UUID id;
    @Column(nullable = false)
    private UUID batchId;
    @Column(nullable = false, length = 32)
    private String videoId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ItemAction action;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ItemStatus status;
    private UUID jobId;
    private UUID analysisId;
    private UUID recordId;
    private Instant uploadDeadlineAt;
    @Column(length = 100)
    private String errorCode;
    @Column(length = 500)
    private String errorMessage;
    private Boolean retryable;
    @Column(nullable = false)
    private Instant updatedAt;

    private AnalysisBatchItem(UUID batchId, String videoId, ItemAction action, ItemStatus status, Instant now) {
        this.id = UUID.randomUUID();
        this.batchId = Objects.requireNonNull(batchId);
        this.videoId = Objects.requireNonNull(videoId);
        this.action = action;
        this.status = status;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public static AnalysisBatchItem collectCaptions(UUID batchId, String videoId, Instant deadline, Instant now) {
        if (!Objects.requireNonNull(deadline).isAfter(now)) {
            throw new IllegalArgumentException("upload deadline must be in the future");
        }
        AnalysisBatchItem item = new AnalysisBatchItem(batchId, videoId,
                ItemAction.COLLECT_CAPTIONS, ItemStatus.AWAITING_UPLOAD, now);
        item.uploadDeadlineAt = deadline;
        return item;
    }

    public static AnalysisBatchItem openJob(UUID batchId, String videoId, UUID jobId, Instant now) {
        AnalysisBatchItem item = new AnalysisBatchItem(batchId, videoId,
                ItemAction.OPEN_JOB, ItemStatus.QUEUED, now);
        item.jobId = Objects.requireNonNull(jobId);
        return item;
    }

    public static AnalysisBatchItem openResult(UUID batchId, String videoId, UUID analysisId, UUID recordId, Instant now) {
        AnalysisBatchItem item = new AnalysisBatchItem(batchId, videoId,
                ItemAction.OPEN_RESULT, ItemStatus.COMPLETED, now);
        item.analysisId = Objects.requireNonNull(analysisId);
        item.recordId = Objects.requireNonNull(recordId);
        return item;
    }

    public void submit(UUID jobId, Instant now) {
        requireAwaitingUpload();
        if (!now.isBefore(uploadDeadlineAt)) {
            throw new IllegalStateException("upload deadline has expired");
        }
        this.jobId = Objects.requireNonNull(jobId);
        this.status = ItemStatus.QUEUED;
        this.updatedAt = now;
    }

    public void failCollection(String code, String message, boolean retryable, Instant now) {
        requireAwaitingUpload();
        this.status = ItemStatus.FAILED;
        this.errorCode = Objects.requireNonNull(code);
        this.errorMessage = Objects.requireNonNull(message);
        this.retryable = retryable;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public boolean expireAwaitingUpload(Instant now) {
        if (status != ItemStatus.AWAITING_UPLOAD || now.isBefore(uploadDeadlineAt)) {
            return false;
        }
        failCollection("CAPTION_UPLOAD_EXPIRED", "자막 업로드 시간이 지났어요. 영상을 다시 선택해 주세요.", false, now);
        return true;
    }

    private void requireAwaitingUpload() {
        if (status != ItemStatus.AWAITING_UPLOAD) {
            throw new IllegalStateException("item is not awaiting upload");
        }
    }
}
