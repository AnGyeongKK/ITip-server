package com.gkk.itip.api.analysis.service;

import com.gkk.itip.api.analysis.dto.request.CreateBatchRequest;
import com.gkk.itip.api.analysis.dto.request.ReportCollectionFailureRequest;
import com.gkk.itip.api.analysis.dto.response.*;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.domain.video.VideoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.gkk.itip.common.exception.ErrorCode.*;

@Service
public class AnalysisBatchService {
    private static final String CREATE_OPERATION = "CREATE_ANALYSIS_BATCH";
    private static final Set<JobStatus> ACTIVE_JOBS = EnumSet.of(JobStatus.QUEUED, JobStatus.PROCESSING, JobStatus.RETRY_WAIT);
    private final AnalysisBatchRepository batches;
    private final AnalysisBatchItemRepository items;
    private final AnalysisJobRepository jobs;
    private final AnalysisRecordRepository records;
    private final VideoRepository videos;
    private final IdempotencyRequestRepository requests;
    private final AnalysisOwnerLock ownerLock;
    private final JsonMapper mapper;
    private final Clock clock;
    private final Duration preparationTtl;
    private final long pollAfterMs;

    public AnalysisBatchService(AnalysisBatchRepository batches, AnalysisBatchItemRepository items,
            AnalysisJobRepository jobs, AnalysisRecordRepository records, VideoRepository videos,
            IdempotencyRequestRepository requests, AnalysisOwnerLock ownerLock, JsonMapper mapper, Clock clock,
            @Value("${itip.analysis.preparation-ttl:PT30M}") Duration preparationTtl,
            @Value("${itip.analysis.next-poll-after-ms:3000}") long pollAfterMs) {
        if (preparationTtl.isNegative() || preparationTtl.isZero() || pollAfterMs <= 0)
            throw new IllegalArgumentException("positive preparation TTL and polling interval required");
        this.batches = batches; this.items = items; this.jobs = jobs; this.records = records;
        this.videos = videos; this.requests = requests; this.ownerLock = ownerLock;
        this.mapper = mapper; this.clock = clock; this.preparationTtl = preparationTtl; this.pollAfterMs = pollAfterMs;
    }

    @Transactional
    public BatchCreatedResponse create(String ownerId, UUID key, CreateBatchRequest request) {
        ownerLock.lock(ownerId);
        String hash = RequestFingerprint.of(mapper, request);
        var replay = requests.findByOwnerIdAndOperationAndKey(ownerId, CREATE_OPERATION, key);
        if (replay.isPresent()) {
            if (!replay.get().matches(hash)) throw new BusinessException(IDEMPOTENCY_CONFLICT);
            return createdResponse(snapshot(ownedBatch(ownerId, replay.get().getTargetId())));
        }

        var selected = new LinkedHashSet<>(request.videoIds());
        if (videos.findAllById(selected).size() != selected.size()) throw new BusinessException(RESOURCE_NOT_FOUND);
        var existingRecords = records.findLatestByOwnerAndVideoIds(ownerId, selected).stream()
                .collect(Collectors.toMap(AnalysisRecord::getVideoId, Function.identity()));
        var activeJobs = jobs.findAllByOwnerIdAndVideoIdInAndStatusIn(ownerId, selected, ACTIVE_JOBS).stream()
                .collect(Collectors.toMap(AnalysisJob::getVideoId, Function.identity()));
        var now = clock.instant();
        var preparing = items.findUnexpiredPreparations(ownerId, selected, now).stream()
                .map(AnalysisBatchItem::getVideoId).collect(Collectors.toSet());
        for (String videoId : selected) {
            if (!existingRecords.containsKey(videoId) && !activeJobs.containsKey(videoId) && preparing.contains(videoId))
                throw new BusinessException(STATE_CONFLICT, "이미 자막을 준비하고 있어요. 기존 분석 배치로 돌아가 주세요.");
        }

        var batch = batches.saveAndFlush(new AnalysisBatch(ownerId, request.playlistId(), now));
        List<AnalysisBatchItem> createdItems = new ArrayList<>();
        for (String videoId : selected) {
            var record = existingRecords.get(videoId);
            var job = activeJobs.get(videoId);
            if (record != null) createdItems.add(AnalysisBatchItem.openResult(batch.getId(), videoId, record.getAnalysisId(), record.getId(), now));
            else if (job != null) createdItems.add(AnalysisBatchItem.openJob(batch.getId(), videoId, job.getId(), now));
            else createdItems.add(AnalysisBatchItem.collectCaptions(batch.getId(), videoId, now.plus(preparationTtl), now));
        }
        items.saveAllAndFlush(createdItems);
        requests.save(new IdempotencyRequest(ownerId, CREATE_OPERATION, key, hash, batch.getId(), now));
        return createdResponse(snapshot(batch));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BatchStatusResponse get(String ownerId, UUID batchId) {
        Snapshot snapshot = snapshot(ownedBatch(ownerId, batchId));
        var playlist = snapshot.batch().getPlaylistId() == null ? null :
                new BatchStatusResponse.PlaylistInfo(snapshot.batch().getPlaylistId(), null, null, null, null);
        boolean terminal = snapshot.counts().completed() + snapshot.counts().failed() == snapshot.counts().total();
        return new BatchStatusResponse(batchId, snapshot.status(), snapshot.counts(), snapshot.views(),
                terminal ? null : pollAfterMs, playlist);
    }

    @Transactional
    public CollectionFailureResponse reportCollectionFailure(String ownerId, UUID batchId, UUID itemId,
                                                              ReportCollectionFailureRequest request) {
        ownerLock.lock(ownerId);
        ownedBatch(ownerId, batchId);
        var item = items.findByIdAndBatchId(itemId, batchId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        if (item.getStatus() != ItemStatus.AWAITING_UPLOAD)
            throw new BusinessException(STATE_CONFLICT, "이미 접수되거나 종료된 항목이에요.");
        var code = request.collectionFailure().code();
        item.failCollection(code.name(), code.message(), false, clock.instant());
        return new CollectionFailureResponse(batchId, itemId, item.getStatus(),
                new BatchStatusResponse.Failure(code.name(), code.message(), false));
    }

    private AnalysisBatch ownedBatch(String ownerId, UUID id) {
        return batches.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
    }

    private Snapshot snapshot(AnalysisBatch batch) {
        List<AnalysisBatchItem> batchItems = items.findAllByBatchIdOrderByUpdatedAtAscIdAsc(batch.getId());
        var jobIds = batchItems.stream().map(AnalysisBatchItem::getJobId).filter(Objects::nonNull).toList();
        Map<UUID, AnalysisJob> jobMap = jobs.findAllById(jobIds).stream().collect(Collectors.toMap(AnalysisJob::getId, Function.identity()));
        var videoIds = batchItems.stream().map(AnalysisBatchItem::getVideoId).toList();
        Map<String, Video> videoMap = videos.findAllById(videoIds).stream().collect(Collectors.toMap(Video::getVideoId, Function.identity()));
        List<BatchStatusResponse.Item> views = batchItems.stream().map(item -> currentItem(batch, item, jobMap, videoMap)).toList();
        Map<ItemStatus, Integer> counts = new EnumMap<>(ItemStatus.class);
        for (ItemStatus status : ItemStatus.values()) counts.put(status, 0);
        for (var item : views) counts.merge(item.status(), 1, Integer::sum);
        var totals = new BatchStatusResponse.Counts(views.size(), counts.get(ItemStatus.AWAITING_UPLOAD), counts.get(ItemStatus.QUEUED),
                counts.get(ItemStatus.PROCESSING), counts.get(ItemStatus.RETRY_WAIT), counts.get(ItemStatus.COMPLETED), counts.get(ItemStatus.FAILED));
        return new Snapshot(batch, batchItems, views, totals, batchStatus(totals));
    }

    private BatchStatusResponse.Item currentItem(AnalysisBatch batch, AnalysisBatchItem item,
                                                Map<UUID, AnalysisJob> jobMap, Map<String, Video> videoMap) {
        var status = item.getStatus();
        UUID analysisId = item.getAnalysisId(), recordId = item.getRecordId();
        AnalysisStage stage = null;
        BatchStatusResponse.Failure error = item.getErrorCode() == null ? null :
                new BatchStatusResponse.Failure(item.getErrorCode(), item.getErrorMessage(), Boolean.TRUE.equals(item.getRetryable()));
        if (item.getJobId() != null) {
            var job = jobMap.get(item.getJobId());
            if (job == null) throw new BusinessException(INTERNAL_ERROR);
            if (!batch.getOwnerId().equals(job.getOwnerId())) throw new BusinessException(RESOURCE_NOT_FOUND);
            if (!item.getVideoId().equals(job.getVideoId())) throw new BusinessException(INTERNAL_ERROR);
            status = ItemStatus.valueOf(job.getStatus().name());
            stage = job.getStage();
            analysisId = status == ItemStatus.COMPLETED ? job.getAnalysisId() : null;
            recordId = status == ItemStatus.COMPLETED ? job.getRecordId() : null;
            error = job.getErrorCode() == null ? null :
                    new BatchStatusResponse.Failure(job.getErrorCode(), job.getErrorMessage(), Boolean.TRUE.equals(job.getRetryable()));
        }
        Video video = videoMap.get(item.getVideoId());
        var metadata = video == null ? null : new BatchStatusResponse.VideoInfo(video.getVideoId(), video.getTitle(), video.getChannelName(),
                video.getChannelImageUrl(), video.getThumbnailUrl(), video.getPublishedAt(), video.getDurationMs());
        return new BatchStatusResponse.Item(item.getId(), item.getVideoId(), status, item.getJobId(), analysisId, recordId,
                error, metadata, stage, status == ItemStatus.AWAITING_UPLOAD ? item.getUploadDeadlineAt() : null);
    }

    private BatchCreatedResponse createdResponse(Snapshot snapshot) {
        List<BatchCreatedResponse.Item> responseItems = new ArrayList<>();
        int fresh = 0, completed = 0, active = 0;
        for (int i = 0; i < snapshot.items().size(); i++) {
            var item = snapshot.items().get(i);
            var view = snapshot.views().get(i);
            switch (item.getAction()) {
                case COLLECT_CAPTIONS -> fresh++;
                case OPEN_RESULT -> completed++;
                case OPEN_JOB -> active++;
            }
            responseItems.add(new BatchCreatedResponse.Item(view.itemId(), view.videoId(), item.getAction(), view.status(),
                    view.jobId(), view.analysisId(), view.recordId(), view.uploadDeadlineAt()));
        }
        return new BatchCreatedResponse(snapshot.batch().getId(), snapshot.status(), List.copyOf(responseItems),
                new BatchCreatedResponse.SelectionSummary(responseItems.size(), fresh, completed, active));
    }

    private String batchStatus(BatchStatusResponse.Counts counts) {
        if (counts.completed() == counts.total()) return "COMPLETED";
        if (counts.failed() == counts.total()) return "FAILED";
        if (counts.completed() + counts.failed() == counts.total()) return "PARTIALLY_FAILED";
        if (counts.queued() + counts.processing() + counts.retryWait() > 0) return "PROCESSING";
        return "PREPARING";
    }

    private record Snapshot(AnalysisBatch batch, List<AnalysisBatchItem> items, List<BatchStatusResponse.Item> views,
                            BatchStatusResponse.Counts counts, String status) {}
}
