package com.gkk.itip.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisRecordRepository extends JpaRepository<AnalysisRecord, UUID> {
    Optional<AnalysisRecord> findFirstByOwnerIdAndVideoIdOrderByAnalyzedAtDescIdDesc(String ownerId, String videoId);
    Optional<AnalysisRecord> findByIdAndOwnerId(UUID id, String ownerId);
    Optional<AnalysisRecord> findByOwnerIdAndAnalysisId(String ownerId, UUID analysisId);

    @Query(value = """
            select distinct on (video_id) * from analysis_records
            where owner_id = :ownerId and video_id in (:videoIds)
            order by video_id, analyzed_at desc, id desc
            """, nativeQuery = true)
    List<AnalysisRecord> findLatestByOwnerAndVideoIds(@Param("ownerId") String ownerId,
                                                     @Param("videoIds") Collection<String> videoIds);
}
