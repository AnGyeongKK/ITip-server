package com.gkk.itip.worker.analysis;

import com.gkk.itip.domain.analysis.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobQueue {
    private final EntityManager em;
    private final AnalysisJobRepository jobs;

    public JobQueue(EntityManager em, AnalysisJobRepository jobs) { this.em = em; this.jobs = jobs; }

    public record Claim(UUID jobId, String ownerId, UUID batchId, UUID batchItemId,
                        String videoId, UUID uploadId, long generation, UUID runToken,
                        AnalysisStage stage, int attempt, UUID analysisId) {}

    @Transactional
    public Optional<Claim> claim(Instant now, Duration leaseDuration) {
        em.createNativeQuery("""
                update analysis_jobs set status = 'FAILED', error_code = 'WORKER_LEASE_EXPIRED',
                    error_message = '분석 실행이 중단됐어요. 다시 시도해 주세요.', retryable = true,
                    run_token = null, lease_until = null, next_attempt_at = null, updated_at = :now
                where status = 'PROCESSING' and attempt >= max_attempts and lease_until <= :now
                """).setParameter("now", now).executeUpdate();
        var ids = em.createNativeQuery("""
                select id from analysis_jobs where attempt < max_attempts and (
                    (status in ('QUEUED','RETRY_WAIT') and next_attempt_at <= :now)
                    or (status = 'PROCESSING' and lease_until <= :now))
                order by created_at, id limit 1 for update skip locked
                """, UUID.class).setParameter("now", now).getResultList();
        if (ids.isEmpty()) return Optional.empty();
        var job = jobs.findById((UUID)ids.getFirst()).orElseThrow();
        UUID token = job.claim(now, leaseDuration);
        return Optional.of(new Claim(job.getId(), job.getOwnerId(), job.getBatchId(), job.getBatchItemId(),
                job.getVideoId(), job.getUploadId(), job.getGeneration(), token, job.getStage(), job.getAttempt(), job.getAnalysisId()));
    }

    @Transactional
    public boolean renew(UUID jobId, UUID token, Instant now, Duration leaseDuration) {
        var job = jobs.findByIdForUpdate(jobId).orElseThrow();
        if (!job.isLeaseOwned(token, now)) return false;
        job.renewLease(token, now, leaseDuration);
        return true;
    }

    @Transactional
    public void advance(UUID jobId, UUID token, AnalysisStage stage, Instant now) {
        var job = jobs.findByIdForUpdate(jobId).orElseThrow(LostJobLeaseException::new);
        if (!job.isLeaseOwned(token, now)) throw new LostJobLeaseException();
        job.changeStage(token, stage, now);
    }

    @Transactional
    public boolean fail(UUID jobId, UUID token, String code, String message, boolean retryable, Instant now, Duration retryDelay) {
        var job = jobs.findByIdForUpdate(jobId).orElseThrow();
        if (!job.isLeaseOwned(token, now)) return false;
        job.fail(token, code, message, retryable, now, retryDelay);
        return true;
    }
}
