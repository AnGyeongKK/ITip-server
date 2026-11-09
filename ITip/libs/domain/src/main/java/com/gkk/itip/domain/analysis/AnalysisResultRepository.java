package com.gkk.itip.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, UUID> {
    Optional<AnalysisResult> findBySourceJobId(UUID sourceJobId);
    Optional<AnalysisResult> findByIdAndOwnerId(UUID id, String ownerId);
    Optional<AnalysisResult> findByIdAndOwnerIdAndChatReadyTrue(UUID id, String ownerId);
}
