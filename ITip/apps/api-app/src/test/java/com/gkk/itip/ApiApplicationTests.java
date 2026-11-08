package com.gkk.itip;

import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.video.Video;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class ApiApplicationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager entityManager;

    @Autowired
    AnalysisResultRepository results;

    @Autowired
    AnalysisRecordRepository records;

    @Autowired
    AnalysisSectionRepository sections;

    @Test
    void flywayMakesVectorStorageAvailable() {
        assertThat(jdbc.queryForObject("select vector_dims('[1,2,3]'::vector)", Integer.class)).isEqualTo(3);
    }

    @Test
    @Transactional
    void storesDraftSectionsCompletedRecordAndCaptionVectorInPostgres() {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        String owner = "result-test";
        String videoId = "dQw4w9WgXcQ";
        entityManager.persist(new Video(videoId));
        AnalysisBatch batch = new AnalysisBatch(owner, null, now);
        entityManager.persist(batch);
        AnalysisBatchItem item = AnalysisBatchItem.collectCaptions(batch.getId(), videoId, now.plusSeconds(600), now);
        entityManager.persist(item);
        entityManager.flush();
        CaptionUpload upload = CaptionUpload.issued(owner, item.getId(), videoId, "test-bucket", "test/captions.json",
                "application/json", 100L, now.plusSeconds(300));
        entityManager.persist(upload);
        entityManager.flush();
        AnalysisJob job = AnalysisJob.queued(owner, batch.getId(), item.getId(), videoId, upload.getId(), 7L, null, now);
        entityManager.persist(job);
        entityManager.flush();

        UUID analysisId = UUID.randomUUID();
        AnalysisResult result = AnalysisResult.draft(analysisId, owner, videoId, job.getId(),
                "{\"schemaVersion\":\"1.0\",\"summary\":{\"oneLine\":\"요약\"}}", now);
        entityManager.persist(result);
        UUID sectionId = UUID.randomUUID();
        entityManager.persist(new AnalysisSection(sectionId, analysisId, "검색 흐름", 0L, 1000L,
                "{\"takeaway\":\"원문 기반 검색\"}"));
        entityManager.flush();
        entityManager.clear();

        assertThat(results.findBySourceJobId(job.getId())).isPresent();
        assertThat(results.findByIdAndOwnerIdAndChatReadyTrue(analysisId, owner)).isEmpty();
        assertThat(sections.findAllByAnalysisIdOrderByStartMsAscIdAsc(analysisId))
                .extracting(AnalysisSection::getId).containsExactly(sectionId);

        result = results.findByIdAndOwnerId(analysisId, owner).orElseThrow();
        result.markReady(now.plusSeconds(2));
        AnalysisRecord record = AnalysisRecord.fromCompleted(result);
        entityManager.persist(record);
        entityManager.flush();
        entityManager.clear();

        assertThat(results.findByIdAndOwnerIdAndChatReadyTrue(analysisId, owner)).isPresent();
        assertThat(records.findFirstByOwnerIdAndVideoIdOrderByAnalyzedAtDescIdDesc(owner, videoId))
                .map(AnalysisRecord::getId).contains(record.getId());
        assertThat(records.findByIdAndOwnerId(record.getId(), "another-owner")).isEmpty();

        jdbc.update("""
                insert into analysis_chunks (id, owner_id, analysis_id, section_ids, source_segment_ids,
                    text, start_ms, end_ms, source_type, embedding, embedding_model, embedding_dimensions)
                values (?, ?, ?, array[?]::uuid[], array['s000001'], ?, 0, 1000, 'CAPTION', '[1,2,3]'::vector, ?, 3)
                """, UUID.randomUUID(), owner, analysisId, sectionId, "원문 자막", "test-embedding-v1");
        assertThat(jdbc.queryForObject("select vector_dims(embedding) from analysis_chunks where analysis_id = ?",
                Integer.class, analysisId)).isEqualTo(3);
        assertThatThrownBy(() -> jdbc.update("""
                insert into analysis_chunks (id, owner_id, analysis_id, section_ids, source_segment_ids,
                    text, start_ms, end_ms, source_type, embedding, embedding_model, embedding_dimensions)
                values (?, ?, ?, array[?]::uuid[], array['s000001'], ?, 0, 1000, 'CAPTION', '[1,2,3]'::vector, ?, 2)
                """, UUID.randomUUID(), owner, analysisId, sectionId, "잘못된 차원", "test-embedding-v1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
