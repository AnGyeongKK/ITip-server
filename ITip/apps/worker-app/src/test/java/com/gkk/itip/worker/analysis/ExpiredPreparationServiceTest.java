package com.gkk.itip.worker.analysis;

import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.video.Video;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.flyway.enabled=true", "itip.worker.scheduling-enabled=false"})
@Testcontainers
class ExpiredPreparationServiceTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired ExpiredPreparationService expiry;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisBatchItemRepository items;
    static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    @BeforeEach void clear() { jdbc.execute("truncate videos,analysis_batches cascade"); }

    @Test void expiresOnlyDueItemsOfTheLockedOwner() {
        UUID due = awaiting("owner-a", 10), future = awaiting("owner-a", 60), other = awaiting("owner-b", 10);
        assertThat(expiry.findOwners(NOW.plusSeconds(20))).containsExactly("owner-a", "owner-b");
        assertThat(expiry.expire("owner-a", NOW.plusSeconds(20))).isEqualTo(1);
        var ended = items.findById(due).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(ItemStatus.FAILED);
        assertThat(ended.getErrorCode()).isEqualTo("CAPTION_UPLOAD_EXPIRED");
        assertThat(ended.getRetryable()).isFalse();
        assertThat(items.findById(future).orElseThrow().getStatus()).isEqualTo(ItemStatus.AWAITING_UPLOAD);
        assertThat(items.findById(other).orElseThrow().getStatus()).isEqualTo(ItemStatus.AWAITING_UPLOAD);
    }

    @Test void cleanupDoesNotCancelASubmittedJob() {
        UUID itemId = awaiting("owner-a", 10);
        tx.executeWithoutResult(status -> {
            var item = em.find(AnalysisBatchItem.class, itemId);
            var upload = CaptionUpload.issued("owner-a", itemId, item.getVideoId(), "test", "test.json", "application/json", 10, NOW.plusSeconds(5));
            upload.consume(1); em.persist(upload); em.flush();
            var job = AnalysisJob.queued("owner-a", item.getBatchId(), itemId, item.getVideoId(), upload.getId(), 1, null, NOW);
            em.persist(job); em.flush(); item.submit(job.getId(), NOW);
        });
        assertThat(expiry.findOwners(NOW.plusSeconds(20))).isEmpty();
        assertThat(expiry.expire("owner-a", NOW.plusSeconds(20))).isZero();
        assertThat(items.findById(itemId).orElseThrow().getStatus()).isEqualTo(ItemStatus.QUEUED);
    }

    @Test void repeatedCleanupDoesNotChangeTheOriginalFailure() {
        UUID id = awaiting("owner-a", 10);
        expiry.expire("owner-a", NOW.plusSeconds(20));
        assertThat(expiry.expire("owner-a", NOW.plusSeconds(30))).isZero();
        assertThat(items.findById(id).orElseThrow().getUpdatedAt()).isEqualTo(NOW.plusSeconds(20));
    }

    private UUID awaiting(String owner, int deadlineSeconds) {
        return tx.execute(status -> {
            String videoId = UUID.randomUUID().toString().substring(0,11); em.persist(new Video(videoId));
            var batch = new AnalysisBatch(owner, null, NOW); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), videoId, NOW.plusSeconds(deadlineSeconds), NOW);
            em.persist(item); em.flush(); return item.getId();
        });
    }
}
