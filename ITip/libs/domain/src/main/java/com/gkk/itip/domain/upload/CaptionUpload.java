package com.gkk.itip.domain.upload;

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
@Table(name = "caption_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaptionUpload {
    @Id
    private UUID id;
    @Column(nullable = false, length = 64)
    private String ownerId;
    @Column(nullable = false)
    private UUID batchItemId;
    @Column(nullable = false, length = 32)
    private String videoId;
    @Column(nullable = false, length = 255)
    private String bucket;
    @Column(nullable = false, length = 1024)
    private String objectKey;
    @Column(nullable = false, length = 100)
    private String contentType;
    @Column(nullable = false)
    private long declaredSizeBytes;
    @Column(nullable = false)
    private Instant urlExpiresAt;
    private Long generation;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UploadStatus status;

    public static CaptionUpload issued(String ownerId, UUID batchItemId, String videoId,
                                       String bucket, String objectKey, String contentType,
                                       long declaredSizeBytes, Instant urlExpiresAt) {
        if (declaredSizeBytes <= 0) {
            throw new IllegalArgumentException("declared size must be positive");
        }
        CaptionUpload upload = new CaptionUpload();
        upload.id = UUID.randomUUID();
        upload.ownerId = Objects.requireNonNull(ownerId);
        upload.batchItemId = Objects.requireNonNull(batchItemId);
        upload.videoId = Objects.requireNonNull(videoId);
        upload.bucket = Objects.requireNonNull(bucket);
        upload.objectKey = Objects.requireNonNull(objectKey);
        upload.contentType = Objects.requireNonNull(contentType);
        upload.declaredSizeBytes = declaredSizeBytes;
        upload.urlExpiresAt = Objects.requireNonNull(urlExpiresAt);
        upload.status = UploadStatus.ISSUED;
        return upload;
    }

    public void markUploaded(long generation) {
        requireUsable();
        requireGeneration(generation);
        this.generation = generation;
        this.status = UploadStatus.UPLOADED;
    }

    public void consume(long generation) {
        if (status == UploadStatus.CONSUMED && Objects.equals(this.generation, generation)) {
            return;
        }
        requireUsable();
        requireGeneration(generation);
        this.generation = generation;
        this.status = UploadStatus.CONSUMED;
    }

    public void reject() {
        requireUsable();
        this.status = UploadStatus.REJECTED;
    }

    private void requireUsable() {
        if (status != UploadStatus.ISSUED && status != UploadStatus.UPLOADED) {
            throw new IllegalStateException("upload is not available for consumption");
        }
    }

    private void requireGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (this.generation != null && this.generation != generation) {
            throw new IllegalStateException("uploaded object generation changed");
        }
    }
}
