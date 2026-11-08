package com.gkk.itip.worker.analysis;

import com.gkk.itip.common.storage.GcsAccessException;
import com.gkk.itip.common.storage.GcsObjects;
import com.gkk.itip.domain.analysis.AnalysisStage;
import com.gkk.itip.worker.analysis.ai.AnalysisAiException;
import com.gkk.itip.worker.analysis.ai.SpringAiAnalysisClient;
import com.gkk.itip.worker.analysis.caption.*;
import com.gkk.itip.worker.analysis.output.AnalysisOutputValidator;
import com.gkk.itip.worker.analysis.output.InvalidAnalysisOutputException;
import com.gkk.itip.worker.config.AnalysisWorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.concurrent.ScheduledFuture;

/** 외부 I/O는 동기로 기다리고 DB 호출마다 별도 Bean의 짧은 트랜잭션을 사용한다. */
@Service
public class AnalysisWorker {
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);
    private final ExpiredPreparationService preparations;
    private final JobQueue queue;
    private final WorkerResultStore store;
    private final GcsObjects objects;
    private final CaptionParser parser;
    private final CaptionChunker chunker;
    private final SpringAiAnalysisClient ai;
    private final AnalysisOutputValidator validator;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final AnalysisWorkerProperties properties;
    private final long maxCaptionBytes;

    public AnalysisWorker(ExpiredPreparationService preparations, JobQueue queue, WorkerResultStore store,
                          GcsObjects objects, CaptionParser parser, CaptionChunker chunker,
                          SpringAiAnalysisClient ai, AnalysisOutputValidator validator, Clock clock,
                          TaskScheduler scheduler, AnalysisWorkerProperties properties,
                          @Value("${itip.analysis.max-caption-bytes}") long maxCaptionBytes) {
        if (maxCaptionBytes <= 0 || maxCaptionBytes >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("caption byte limit must be positive and below Integer.MAX_VALUE");
        this.preparations = preparations;
        this.queue = queue;
        this.store = store;
        this.objects = objects;
        this.parser = parser;
        this.chunker = chunker;
        this.ai = ai;
        this.validator = validator;
        this.clock = clock;
        this.scheduler = scheduler;
        this.properties = properties;
        this.maxCaptionBytes = maxCaptionBytes;
    }

    /** true는 이번 호출에서 한 작업을 선점했다는 뜻이며 완료 성공을 뜻하지 않는다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean runNext() {
        expirePreparations();
        var claim = queue.claim(clock.instant(), properties.leaseDuration());
        if (claim.isEmpty()) return false;
        process(claim.get());
        return true;
    }

    private void expirePreparations() {
        try {
            for (String owner : preparations.findOwners(clock.instant())) {
                try { preparations.expire(owner, clock.instant()); }
                catch (RuntimeException exception) {
                    log.warn("PREPARATION_EXPIRY_FAILED type={}", exception.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException exception) {
            log.warn("PREPARATION_DISCOVERY_FAILED type={}", exception.getClass().getSimpleName());
        }
    }

    private void process(JobQueue.Claim claim) {
        ScheduledFuture<?> heartbeat = null;
        MDC.put("jobId", claim.jobId().toString());
        try {
            heartbeat = scheduler.scheduleWithFixedDelay(() -> renew(claim),
                    clock.instant().plus(properties.heartbeatDelay()), properties.heartbeatDelay());
            var existing = store.draft(claim, clock.instant());
            CaptionDocument captions = null;
            WorkerResultStore.Draft draft;
            if (existing.isPresent()) {
                draft = existing.get();
            } else {
                captions = readCaptions(claim);
                ai.checkConfigured();
                queue.advance(claim.jobId(), claim.runToken(), AnalysisStage.SUMMARIZING, clock.instant());
                var document = validator.validate(ai.generate(captions), captions);
                draft = store.saveDraft(claim, document, clock.instant());
            }
            if (!draft.indexed()) {
                if (captions == null) captions = readCaptions(claim);
                var chunks = chunker.split(draft.analysisId(), captions, draft.document().sections(), properties.chunkCodePoints());
                var embeddings = ai.embed(chunks);
                store.saveIndex(claim, draft.analysisId(), chunks, embeddings.model(), embeddings.vectors(), clock.instant());
            }
            store.complete(claim, draft.analysisId(), clock.instant());
            log.info("ANALYSIS_COMPLETED jobId={}", claim.jobId());
        } catch (LostJobLeaseException exception) {
            log.info("WORKER_LEASE_LOST jobId={}", claim.jobId());
        } catch (RuntimeException exception) {
            var failure = failure(exception);
            log.warn("ANALYSIS_FAILED jobId={} code={} type={}", claim.jobId(), failure.code(), exception.getClass().getSimpleName());
            // 실패 기록 자체의 DB 오류는 호출자에게 전달하고 lease 만료 복구에 맡긴다.
            queue.fail(claim.jobId(), claim.runToken(), failure.code(), failure.message(), failure.retryable(),
                    clock.instant(), properties.retryDelay());
        } finally {
            if (heartbeat != null) heartbeat.cancel(false);
            MDC.remove("jobId");
        }
    }

    private CaptionDocument readCaptions(JobQueue.Claim claim) {
        var source = store.source(claim, clock.instant());
        if (source.durationMs() == null) throw new InvalidCaptionException("VIDEO_METADATA_REQUIRED");
        byte[] bytes = objects.read(source.bucket(), source.objectKey(), source.generation(), maxCaptionBytes);
        if (bytes == null || bytes.length != source.declaredSizeBytes())
            throw new InvalidCaptionException("CAPTION_SIZE_MISMATCH");
        return parser.parse(bytes, claim.videoId(), source.durationMs(), maxCaptionBytes);
    }

    private void renew(JobQueue.Claim claim) {
        MDC.put("jobId", claim.jobId().toString());
        try {
            if (!queue.renew(claim.jobId(), claim.runToken(), clock.instant(), properties.leaseDuration()))
                log.info("WORKER_HEARTBEAT_LEASE_LOST jobId={}", claim.jobId());
        } catch (RuntimeException exception) {
            // heartbeat가 실패해도 새 실행을 실패 전환하지 않는다. 저장 단계에서 token을 재검사한다.
            log.warn("WORKER_HEARTBEAT_FAILED jobId={} type={}", claim.jobId(), exception.getClass().getSimpleName());
        } finally { MDC.remove("jobId"); }
    }

    private Failure failure(RuntimeException exception) {
        if (exception instanceof InvalidCaptionException caption)
            return new Failure(caption.getCode(), "자막을 확인할 수 없어요. 영상을 다시 선택해 주세요.", false);
        if (exception instanceof InvalidAnalysisOutputException)
            return new Failure("AI_INVALID_RESPONSE", "분석 응답을 확인할 수 없어요.", false);
        if (exception instanceof AnalysisAiException model)
            return new Failure(model.code(), "분석 모델 요청을 완료하지 못했어요.", model.retryable());
        if (exception instanceof GcsAccessException storage) {
            String code = switch (storage.getReason()) {
                case OBJECT_NOT_FOUND, OBJECT_VERSION_CHANGED -> "CAPTION_REUPLOAD_REQUIRED";
                default -> storage.getReason().name();
            };
            return new Failure(code, "자막 파일을 읽을 수 없어요.",
                    storage.getReason() == GcsAccessException.Reason.STORAGE_UNAVAILABLE && storage.isRetryable());
        }
        if (exception instanceof TransientDataAccessException || exception instanceof RecoverableDataAccessException
                || exception instanceof DataAccessResourceFailureException)
            return new Failure("WORKER_DATABASE_UNAVAILABLE", "분석 저장을 잠시 완료하지 못했어요.", true);
        if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException
                || exception instanceof NonTransientDataAccessException)
            return new Failure("WORKER_DATA_INVALID", "분석 데이터를 확인할 수 없어요.", false);
        return new Failure("WORKER_ERROR", "분석 실행을 완료하지 못했어요.", false);
    }

    private record Failure(String code, String message, boolean retryable) {}
}
