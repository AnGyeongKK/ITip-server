package com.gkk.itip.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AnalysisBatchRepository extends JpaRepository<AnalysisBatch, UUID> {
    Optional<AnalysisBatch> findByIdAndOwnerId(UUID id, String ownerId);
}
