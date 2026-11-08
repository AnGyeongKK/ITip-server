package com.gkk.itip.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisBatchItemRepository extends JpaRepository<AnalysisBatchItem, UUID> {
    List<AnalysisBatchItem> findAllByBatchIdOrderByUpdatedAtAscIdAsc(UUID batchId);
    Optional<AnalysisBatchItem> findByIdAndBatchId(UUID id, UUID batchId);
    List<AnalysisBatchItem> findAllByBatchIdInAndStatus(Collection<UUID> batchIds, ItemStatus status);
}
