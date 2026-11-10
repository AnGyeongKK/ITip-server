package com.gkk.itip.api.upload.service;

import com.gkk.itip.api.upload.dto.request.CreateCaptionUploadRequest;
import com.gkk.itip.api.upload.dto.response.CaptionUploadCreatedResponse;
import com.gkk.itip.api.upload.dto.response.CaptionUploadStatusResponse;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.storage.GcsAccessException;
import com.gkk.itip.common.storage.GcsObjects;
import com.gkk.itip.domain.analysis.AnalysisBatchItem;
import com.gkk.itip.domain.analysis.AnalysisBatchItemRepository;
import com.gkk.itip.domain.analysis.AnalysisBatchRepository;
import com.gkk.itip.domain.analysis.AnalysisOwnerLock;
import com.gkk.itip.domain.analysis.ItemStatus;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.upload.CaptionUploadRepository;
import com.gkk.itip.domain.upload.UploadStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.BATCH_ITEM_NOT_UPLOADABLE;
import static com.gkk.itip.common.exception.ErrorCode.CAPTION_FILE_TOO_LARGE;
import static com.gkk.itip.common.exception.ErrorCode.INVALID_REQUEST;
import static com.gkk.itip.common.exception.ErrorCode.RESOURCE_NOT_FOUND;
import static com.gkk.itip.common.exception.ErrorCode.STORAGE_UNAVAILABLE;

@Service
public class CaptionUploadService {
    private final AnalysisBatchRepository batches;
    private final AnalysisBatchItemRepository items;
    private final CaptionUploadRepository uploads;
    private final AnalysisOwnerLock ownerLock;
    private final GcsObjects objects;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String bucket;
    private final Duration urlTtl;
    private final long maxBytes;

    public CaptionUploadService(AnalysisBatchRepository batches, AnalysisBatchItemRepository items,
            CaptionUploadRepository uploads, AnalysisOwnerLock ownerLock, GcsObjects objects,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${itip.upload.bucket:}") String bucket,
            @Value("${itip.upload.url-ttl:PT10M}") Duration urlTtl,
            @Value("${itip.analysis.max-caption-bytes:1048576}") long maxBytes) {
        if (urlTtl.toSeconds() < 1 || urlTtl.compareTo(Duration.ofDays(7)) > 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("invalid caption upload limits");
        }
        this.batches = batches;
        this.items = items;
        this.uploads = uploads;
        this.ownerLock = ownerLock;
        this.objects = objects;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.bucket = bucket;
        this.urlTtl = urlTtl;
        this.maxBytes = maxBytes;
    }

    public CaptionUploadCreatedResponse create(String ownerId, CreateCaptionUploadRequest request) {
        if (request.sizeBytes() > maxBytes) throw new BusinessException(CAPTION_FILE_TOO_LARGE);

        var item = uploadableItem(ownerId, request);
        var signedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        long validSeconds = Math.min(urlTtl.toSeconds(), Duration.between(signedAt, item.getUploadDeadlineAt()).toSeconds());
        if (validSeconds <= 0) throw new BusinessException(BATCH_ITEM_NOT_UPLOADABLE);
        if (bucket.isBlank()) throw new BusinessException(STORAGE_UNAVAILABLE);

        var expiresAt = signedAt.plusSeconds(validSeconds);
        var objectKey = "captions/" + item.getId() + "/" + UUID.randomUUID() + ".json";
        var upload = CaptionUpload.issued(ownerId, item.getId(), item.getVideoId(), bucket, objectKey,
                request.contentType(), request.sizeBytes(), expiresAt);
        GcsObjects.SignedPut signed;
        try {
            signed = objects.signPut(bucket, objectKey, upload.getContentType(), upload.getDeclaredSizeBytes(), validSeconds);
        } catch (GcsAccessException exception) {
            throw new BusinessException(STORAGE_UNAVAILABLE);
        }

        transactions.executeWithoutResult(transaction -> {
            ownerLock.lock(ownerId);
            uploadableItem(ownerId, request);
            if (!clock.instant().isBefore(expiresAt)) throw new BusinessException(BATCH_ITEM_NOT_UPLOADABLE);
            uploads.save(upload);
        });
        return new CaptionUploadCreatedResponse(upload.getId(), "PUT", signed.url(), signed.headers(), expiresAt, maxBytes);
    }

    public CaptionUploadStatusResponse get(String ownerId, UUID uploadId) {
        var upload = uploads.findByIdAndOwnerId(uploadId, ownerId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        var status = upload.getStatus();
        UUID jobId = null;
        if (status == UploadStatus.CONSUMED) {
            jobId = items.findById(upload.getBatchItemId()).map(AnalysisBatchItem::getJobId).orElse(null);
        } else if (status == UploadStatus.ISSUED || status == UploadStatus.UPLOADED) {
            status = storageStatus(upload);
        }
        return new CaptionUploadStatusResponse(uploadId, upload.getBatchItemId(), status, upload.getUrlExpiresAt(), jobId);
    }

    private AnalysisBatchItem uploadableItem(String ownerId, CreateCaptionUploadRequest request) {
        batches.findByIdAndOwnerId(request.batchId(), ownerId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        var item = items.findByIdAndBatchId(request.batchItemId(), request.batchId())
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        if (!item.getVideoId().equals(request.videoId())) throw new BusinessException(INVALID_REQUEST);
        if (item.getStatus() != ItemStatus.AWAITING_UPLOAD || !clock.instant().isBefore(item.getUploadDeadlineAt())) {
            throw new BusinessException(BATCH_ITEM_NOT_UPLOADABLE);
        }
        return item;
    }

    private UploadStatus storageStatus(CaptionUpload upload) {
        try {
            var metadata = objects.metadata(upload.getBucket(), upload.getObjectKey(), upload.getGeneration());
            if (metadata.sizeBytes() <= 0 || metadata.sizeBytes() > maxBytes
                    || metadata.sizeBytes() != upload.getDeclaredSizeBytes()
                    || !upload.getContentType().equals(metadata.contentType())) {
                return UploadStatus.REJECTED;
            }
            return UploadStatus.UPLOADED;
        } catch (GcsAccessException exception) {
            return switch (exception.getReason()) {
                case OBJECT_NOT_FOUND -> upload.getGeneration() != null ? UploadStatus.REJECTED
                        : clock.instant().isBefore(upload.getUrlExpiresAt()) ? UploadStatus.ISSUED : UploadStatus.EXPIRED;
                case OBJECT_VERSION_CHANGED, CAPTION_TOO_LARGE, INVALID_OBJECT_METADATA -> UploadStatus.REJECTED;
                case STORAGE_NOT_CONFIGURED, STORAGE_UNAVAILABLE -> throw new BusinessException(STORAGE_UNAVAILABLE);
            };
        }
    }
}
