package com.gkk.itip.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalysisSectionRepository extends JpaRepository<AnalysisSection, UUID> {
    List<AnalysisSection> findAllByAnalysisIdOrderByStartMsAscIdAsc(UUID analysisId);
}
