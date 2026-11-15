package com.gkk.itip.worker.analysis;

import com.gkk.itip.domain.analysis.AnalysisBatchPushOutbox;
import com.gkk.itip.domain.analysis.AnalysisOwnerLock;
import com.gkk.itip.domain.analysis.JobStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
class ExpiredJobLeaseService {
    private static final String EXPIRED = "status = 'PROCESSING' and attempt >= max_attempts and lease_until <= :now";

    private final EntityManager em;
    private final AnalysisOwnerLock ownerLock;
    private final AnalysisBatchPushOutbox pushOutbox;

    ExpiredJobLeaseService(EntityManager em, AnalysisOwnerLock ownerLock, AnalysisBatchPushOutbox pushOutbox) {
        this.em = em;
        this.ownerLock = ownerLock;
        this.pushOutbox = pushOutbox;
    }

    @Transactional(readOnly = true)
    public List<String> findOwners(Instant now) {
        return em.createQuery("""
                select distinct j.ownerId from AnalysisJob j
                where j.status = :status and j.attempt >= j.maxAttempts and j.leaseUntil <= :now
                """, String.class).setParameter("status", JobStatus.PROCESSING).setParameter("now", now).getResultList();
    }

    @Transactional
    public void expire(String ownerId, Instant now) {
        ownerLock.lock(ownerId);
        int changed = em.createNativeQuery("""
                update analysis_jobs set status = 'FAILED', error_code = 'WORKER_LEASE_EXPIRED',
                    error_message = '분석 실행이 중단됐어요. 다시 시도해 주세요.', retryable = true,
                    run_token = null, lease_until = null, next_attempt_at = null, updated_at = :now
                where owner_id = :ownerId and
                """ + EXPIRED).setParameter("now", now).setParameter("ownerId", ownerId).executeUpdate();
        if (changed > 0) pushOutbox.enqueueFinishedBatches(ownerId, now);
    }
}
