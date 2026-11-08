package com.gkk.itip.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AnalysisRecordRepository extends JpaRepository<AnalysisRecord, UUID> {
    Optional<AnalysisRecord> findFirstByOwnerIdAndVideoIdOrderByAnalyzedAtDescIdDesc(String ownerId, String videoId);
    Optional<AnalysisRecord> findByIdAndOwnerId(UUID id, String ownerId);
    Optional<AnalysisRecord> findByOwnerIdAndAnalysisId(String ownerId, UUID analysisId);
}
