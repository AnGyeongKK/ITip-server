package com.gkk.itip.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisBatchItemRepository extends JpaRepository<AnalysisBatchItem, UUID> {
    List<AnalysisBatchItem> findAllByBatchIdOrderByUpdatedAtAscIdAsc(UUID batchId);
    Optional<AnalysisBatchItem> findByIdAndBatchId(UUID id, UUID batchId);
    List<AnalysisBatchItem> findAllByBatchIdInAndStatus(Collection<UUID> batchIds, ItemStatus status);

    @Query("""
            select item from AnalysisBatchItem item, AnalysisBatch batch
            where item.batchId = batch.id and batch.ownerId = :ownerId
              and item.videoId in :videoIds and item.status = com.gkk.itip.domain.analysis.ItemStatus.AWAITING_UPLOAD
              and item.uploadDeadlineAt > :now
            """)
    List<AnalysisBatchItem> findUnexpiredPreparations(@Param("ownerId") String ownerId,
            @Param("videoIds") Collection<String> videoIds, @Param("now") Instant now);

    @Query("""
            select distinct batch.ownerId from AnalysisBatchItem item, AnalysisBatch batch
            where item.batchId = batch.id and item.status = com.gkk.itip.domain.analysis.ItemStatus.AWAITING_UPLOAD
              and item.uploadDeadlineAt <= :now order by batch.ownerId
            """)
    List<String> findExpiredPreparationOwners(@Param("now") Instant now);

    @Query("""
            select item from AnalysisBatchItem item, AnalysisBatch batch
            where item.batchId = batch.id and batch.ownerId = :ownerId
              and item.status = com.gkk.itip.domain.analysis.ItemStatus.AWAITING_UPLOAD
              and item.uploadDeadlineAt <= :now
            """)
    List<AnalysisBatchItem> findExpiredPreparations(@Param("ownerId") String ownerId, @Param("now") Instant now);
}
