package com.gkk.itip.domain.analysis;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {
    Optional<AnalysisJob> findByIdAndOwnerId(UUID id, String ownerId);
    Optional<AnalysisJob> findFirstByOwnerIdAndVideoIdAndStatusInOrderByCreatedAtDescIdDesc(
            String ownerId, String videoId, Collection<JobStatus> statuses);
    List<AnalysisJob> findAllByBatchId(UUID batchId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from AnalysisJob job where job.id = :id")
    Optional<AnalysisJob> findByIdForUpdate(@Param("id") UUID id);
}
