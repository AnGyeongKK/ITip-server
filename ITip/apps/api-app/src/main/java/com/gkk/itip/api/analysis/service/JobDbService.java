package com.gkk.itip.api.analysis.service;

import com.gkk.itip.api.analysis.dto.request.SubmitJobRequest;
import com.gkk.itip.api.analysis.dto.response.*;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.storage.GcsObjects;
import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.upload.CaptionUploadRepository;
import com.gkk.itip.domain.upload.UploadStatus;
import com.gkk.itip.domain.video.VideoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

import static com.gkk.itip.common.exception.ErrorCode.*;

@Service
public class JobDbService {
    private static final String SUBMIT = "SUBMIT_ANALYSIS_JOB", RETRY = "RETRY_ANALYSIS_JOB";
    private static final Set<JobStatus> ACTIVE = EnumSet.of(JobStatus.QUEUED, JobStatus.PROCESSING, JobStatus.RETRY_WAIT);
    private final AnalysisBatchRepository batches;
    private final AnalysisBatchItemRepository items;
    private final AnalysisJobRepository jobs;
    private final CaptionUploadRepository uploads;
    private final AnalysisRecordRepository records;
    private final AnalysisResultRepository results;
    private final VideoRepository videos;
    private final IdempotencyRequestRepository requests;
    private final AnalysisOwnerLock ownerLock;
    private final JsonMapper mapper;
    private final Clock clock;
    private final long maxCaptionBytes, pollAfterMs;
    private final Duration preparationTtl;

    public JobDbService(AnalysisBatchRepository batches, AnalysisBatchItemRepository items, AnalysisJobRepository jobs,
            CaptionUploadRepository uploads, AnalysisRecordRepository records, AnalysisResultRepository results, VideoRepository videos,
            IdempotencyRequestRepository requests, AnalysisOwnerLock ownerLock, JsonMapper mapper, Clock clock,
            @Value("${itip.analysis.max-caption-bytes:1048576}") long maxCaptionBytes,
            @Value("${itip.analysis.next-poll-after-ms:3000}") long pollAfterMs,
            @Value("${itip.analysis.preparation-ttl:PT30M}") Duration preparationTtl) {
        if (maxCaptionBytes <= 0 || pollAfterMs <= 0 || preparationTtl.isNegative() || preparationTtl.isZero())
            throw new IllegalArgumentException("positive analysis limits required");
        this.batches = batches; this.items = items; this.jobs = jobs; this.uploads = uploads; this.records = records; this.results = results;
        this.videos = videos; this.requests = requests; this.ownerLock = ownerLock; this.mapper = mapper; this.clock = clock;
        this.maxCaptionBytes = maxCaptionBytes; this.pollAfterMs = pollAfterMs; this.preparationTtl = preparationTtl;
    }

    @Transactional
    public SubmissionPlan prepareSubmit(String ownerId, UUID key, SubmitJobRequest request) {
        ownerLock.lock(ownerId);
        var context = submission(ownerId, key, request);
        return new SubmissionPlan(context.response(), context.upload() == null ? null : snapshot(context.upload()));
    }

    @Transactional
    public JobSubmittedResponse admitSubmit(String ownerId, UUID key, SubmitJobRequest request,
                                            UploadSnapshot checked, GcsObjects.Metadata metadata) {
        ownerLock.lock(ownerId);
        var context = submission(ownerId, key, request);
        if (context.response() != null) return context.response();
        if (!snapshot(context.upload()).equals(checked)) throw new BusinessException(UPLOAD_NOT_READY);
        validateMetadata(context.upload(), metadata, false);
        var now = clock.instant();
        AnalysisJob job = completedJob(ownerId, context.item().getVideoId());
        if (job != null) {
            context.item().reuseResult(job.getId(), job.getAnalysisId(), job.getRecordId(), now);
        } else {
            job = jobs.findFirstByOwnerIdAndVideoIdAndStatusInOrderByCreatedAtDescIdDesc(ownerId, context.item().getVideoId(), ACTIVE).orElse(null);
            if (job == null) {
                job = jobs.saveAndFlush(AnalysisJob.queued(ownerId, request.batchId(), request.batchItemId(), context.item().getVideoId(),
                        request.uploadId(), metadata.generation(), null, now));
            }
            context.item().submit(job.getId(), now);
        }
        context.upload().consume(metadata.generation());
        remember(ownerId, SUBMIT, key, request, job.getId());
        return submitted(job, request);
    }

    private SubmissionContext submission(String ownerId, UUID key, SubmitJobRequest request) {
        var replay = replay(ownerId, SUBMIT, key, request);
        if (replay != null) return new SubmissionContext(null, null, submitted(replay, request));
        batches.findByIdAndOwnerId(request.batchId(), ownerId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        var item = items.findByIdAndBatchId(request.batchItemId(), request.batchId()).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        var upload = uploads.findByIdAndOwnerId(request.uploadId(), ownerId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        if (!upload.getBatchItemId().equals(item.getId()) || !upload.getVideoId().equals(item.getVideoId()))
            throw new BusinessException(STATE_CONFLICT);
        if (item.getJobId() != null) {
            var job = ownedJob(ownerId, item.getJobId());
            if (!job.getVideoId().equals(item.getVideoId()) || upload.getStatus() != UploadStatus.CONSUMED)
                throw new BusinessException(STATE_CONFLICT);
            requireCompletedRecord(job);
            remember(ownerId, SUBMIT, key, request, job.getId());
            return new SubmissionContext(null, null, submitted(job, request));
        }
        if (item.getStatus() != ItemStatus.AWAITING_UPLOAD) throw new BusinessException(STATE_CONFLICT);
        var now = clock.instant();
        if (!now.isBefore(item.getUploadDeadlineAt())
                || (upload.getStatus() != UploadStatus.ISSUED && upload.getStatus() != UploadStatus.UPLOADED))
            throw new BusinessException(UPLOAD_NOT_READY);
        validateDeclaration(upload);
        return new SubmissionContext(item, upload, null);
    }

    @Transactional(readOnly = true)
    public JobStatusResponse get(String ownerId, UUID jobId) {
        var job = ownedJob(ownerId, jobId);
        boolean complete = job.getStatus() == JobStatus.COMPLETED;
        boolean terminal = complete || job.getStatus() == JobStatus.FAILED;
        var failure = job.getErrorCode() == null ? null : new BatchStatusResponse.Failure(job.getErrorCode(), job.getErrorMessage(), Boolean.TRUE.equals(job.getRetryable()));
        var video = videos.findById(job.getVideoId()).map(v -> new BatchStatusResponse.VideoInfo(v.getVideoId(), v.getTitle(), v.getChannelName(),
                v.getChannelImageUrl(), v.getThumbnailUrl(), v.getPublishedAt(), v.getDurationMs())).orElse(null);
        return new JobStatusResponse(job.getId(), job.getBatchId(), job.getVideoId(), job.getStatus(), job.getStage(), job.getAttempt(),
                job.getMaxAttempts(), complete ? job.getAnalysisId() : null, complete ? job.getRecordId() : null, failure,
                terminal ? null : pollAfterMs, job.getUpdatedAt(), video);
    }

    @Transactional
    public RetryPlan prepareRetry(String ownerId, UUID key, UUID previousJobId) {
        ownerLock.lock(ownerId);
        var context = retryContext(ownerId, key, previousJobId);
        return new RetryPlan(context.response(), context.upload() == null ? null : snapshot(context.upload()));
    }

    @Transactional
    public JobRetriedResponse admitRetry(String ownerId, UUID key, UUID previousJobId,
                                         UploadSnapshot checked, GcsObjects.Metadata metadata) {
        ownerLock.lock(ownerId);
        var context = retryContext(ownerId, key, previousJobId);
        if (context.response() != null) return context.response();
        if (!snapshot(context.upload()).equals(checked)) throw new BusinessException(CAPTION_REUPLOAD_REQUIRED);
        validateMetadata(context.upload(), metadata, true);
        var now = clock.instant();
        var batch = batches.saveAndFlush(new AnalysisBatch(ownerId, null, now));
        var item = items.saveAndFlush(AnalysisBatchItem.collectCaptions(batch.getId(), context.previous().getVideoId(), now.plus(preparationTtl), now));
        var job = AnalysisJob.queued(ownerId, batch.getId(), item.getId(), context.previous().getVideoId(), context.upload().getId(),
                context.previous().getGeneration(), previousJobId, now);
        job.reuseProgress(context.previous());
        jobs.saveAndFlush(job);
        item.submit(job.getId(), now);
        remember(ownerId, RETRY, key, previousJobId, job.getId());
        return retried(job, previousJobId);
    }

    private RetryContext retryContext(String ownerId, UUID key, UUID previousJobId) {
        var replay = replay(ownerId, RETRY, key, previousJobId);
        if (replay != null) return new RetryContext(null, null, retried(replay, previousJobId));
        var previous = jobs.findByIdForUpdate(previousJobId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        if (!ownerId.equals(previous.getOwnerId())) throw new BusinessException(RESOURCE_NOT_FOUND);
        if (previous.getStatus() != JobStatus.FAILED || !Boolean.TRUE.equals(previous.getRetryable()))
            throw new BusinessException(JOB_NOT_RETRYABLE);
        AnalysisJob completed = completedJob(ownerId, previous.getVideoId());
        if (completed != null) {
            remember(ownerId, RETRY, key, previousJobId, completed.getId());
            return new RetryContext(null, null, retried(completed, previousJobId));
        }
        if (previous.getAnalysisId() != null && results.findByIdAndOwnerIdAndChatReadyTrue(previous.getAnalysisId(), ownerId).isPresent())
            throw new BusinessException(JOB_NOT_RETRYABLE);
        if (jobs.findFirstByOwnerIdAndVideoIdAndStatusInOrderByCreatedAtDescIdDesc(ownerId, previous.getVideoId(), ACTIVE).isPresent())
            throw new BusinessException(JOB_NOT_RETRYABLE);
        var upload = uploads.findByIdAndOwnerId(previous.getUploadId(), ownerId).orElseThrow(() -> new BusinessException(CAPTION_REUPLOAD_REQUIRED));
        if (!upload.getVideoId().equals(previous.getVideoId()) || upload.getGeneration() == null
                || upload.getGeneration() != previous.getGeneration() || upload.getStatus() != UploadStatus.CONSUMED)
            throw new BusinessException(CAPTION_REUPLOAD_REQUIRED);
        validateDeclaration(upload);
        return new RetryContext(previous, upload, null);
    }

    private void validateDeclaration(CaptionUpload upload) {
        if (upload.getDeclaredSizeBytes() > maxCaptionBytes) throw new BusinessException(CAPTION_TOO_LARGE);
        if (!"application/json".equals(upload.getContentType())) throw new BusinessException(UPLOAD_NOT_READY);
    }

    private void validateMetadata(CaptionUpload upload, GcsObjects.Metadata metadata, boolean retry) {
        if (metadata.sizeBytes() > maxCaptionBytes) throw new BusinessException(CAPTION_TOO_LARGE);
        if (upload.getGeneration() != null && upload.getGeneration() != metadata.generation())
            throw new BusinessException(retry ? CAPTION_REUPLOAD_REQUIRED : UPLOAD_NOT_READY);
        if (metadata.generation() <= 0 || metadata.sizeBytes() <= 0 || metadata.sizeBytes() != upload.getDeclaredSizeBytes()
                || !"application/json".equals(metadata.contentType()) || !upload.getContentType().equals(metadata.contentType()))
            throw new BusinessException(UPLOAD_NOT_READY);
    }

    private AnalysisJob completedJob(String ownerId, String videoId) {
        return records.findFirstByOwnerIdAndVideoIdOrderByAnalyzedAtDescIdDesc(ownerId, videoId).map(record ->
                jobs.findFirstByOwnerIdAndAnalysisIdAndStatusOrderByCreatedAtDescIdDesc(ownerId, record.getAnalysisId(), JobStatus.COMPLETED)
                        .filter(job -> record.getId().equals(job.getRecordId()) && videoId.equals(job.getVideoId()))
                        .orElseThrow(() -> new BusinessException(INTERNAL_ERROR))).orElse(null);
    }

    private AnalysisJob replay(String ownerId, String operation, UUID key, Object body) {
        var request = requests.findByOwnerIdAndOperationAndKey(ownerId, operation, key);
        if (request.isEmpty()) return null;
        if (!request.get().matches(RequestFingerprint.of(mapper, body))) throw new BusinessException(IDEMPOTENCY_CONFLICT);
        var job = ownedJob(ownerId, request.get().getTargetId()); requireCompletedRecord(job); return job;
    }

    private void requireCompletedRecord(AnalysisJob job) {
        if (job.getStatus() == JobStatus.COMPLETED && records.findByIdAndOwnerId(job.getRecordId(), job.getOwnerId())
                .filter(record -> job.getAnalysisId().equals(record.getAnalysisId()) && job.getVideoId().equals(record.getVideoId())).isEmpty())
            throw new BusinessException(STATE_CONFLICT);
    }

    private AnalysisJob ownedJob(String ownerId, UUID id) {
        return jobs.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
    }
    private void remember(String ownerId, String operation, UUID key, Object body, UUID target) {
        requests.save(new IdempotencyRequest(ownerId, operation, key, RequestFingerprint.of(mapper, body), target, clock.instant()));
    }
    private JobSubmittedResponse submitted(AnalysisJob job, SubmitJobRequest request) {
        boolean completed = job.getStatus() == JobStatus.COMPLETED;
        return new JobSubmittedResponse(job.getId(), request.batchId(), request.batchItemId(), job.getStatus(), url(job.getId()),
                completed ? job.getAnalysisId() : null, completed ? job.getRecordId() : null);
    }
    private JobRetriedResponse retried(AnalysisJob job, UUID previousJobId) {
        return new JobRetriedResponse(job.getId(), previousJobId, job.getBatchId(), job.getStatus(), url(job.getId()));
    }
    private String url(UUID jobId) { return "/api/v1/analysis-jobs/" + jobId; }
    private UploadSnapshot snapshot(CaptionUpload upload) {
        return new UploadSnapshot(upload.getId(), upload.getBucket(), upload.getObjectKey(), upload.getGeneration(), upload.getDeclaredSizeBytes(), upload.getContentType());
    }
    public record UploadSnapshot(UUID uploadId, String bucket, String objectKey, Long generation, long sizeBytes, String contentType) {}
    public record SubmissionPlan(JobSubmittedResponse response, UploadSnapshot upload) {}
    public record RetryPlan(JobRetriedResponse response, UploadSnapshot upload) {}
    private record SubmissionContext(AnalysisBatchItem item, CaptionUpload upload, JobSubmittedResponse response) {}
    private record RetryContext(AnalysisJob previous, CaptionUpload upload, JobRetriedResponse response) {}
}
