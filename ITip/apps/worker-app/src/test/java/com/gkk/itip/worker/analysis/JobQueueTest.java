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

import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {"spring.flyway.enabled=true", "itip.worker.scheduling-enabled=false"})
@Testcontainers
class JobQueueTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JobQueue queue;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired AnalysisJobRepository jobs;
    @Autowired JdbcTemplate jdbc;
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @BeforeEach void clear() { jdbc.execute("truncate analysis_batches, videos cascade"); }

    @Test void separateConsumersClaimDifferentJobsAndNoExternalCallKeepsTheTransactionOpen() throws Exception {
        fixture(); fixture();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Callable<JobQueue.Claim> task = () -> { ready.countDown(); go.await(); return queue.claim(NOW, LEASE).orElseThrow(); };
            var a = executor.submit(task); var b = executor.submit(task);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS).jobId()).isNotEqualTo(b.get(5, TimeUnit.SECONDS).jobId());
        }
        assertThat(queue.claim(NOW, LEASE)).isEmpty();
        assertThat(jobs.findAll()).allMatch(j -> j.getStatus() == JobStatus.PROCESSING && j.getAttempt() == 1);
    }

    @Test void expiredLeaseGetsANewTokenAndStaleWritesAreRejected() {
        UUID id = fixture(); var old = queue.claim(NOW, LEASE).orElseThrow();
        var current = queue.claim(NOW.plusSeconds(30), LEASE).orElseThrow();
        assertThat(current.jobId()).isEqualTo(id);
        assertThat(current.runToken()).isNotEqualTo(old.runToken());
        assertThat(queue.fail(id, old.runToken(), "UPSTREAM_FAILED", "실패", true, NOW.plusSeconds(31), Duration.ZERO)).isFalse();
        assertThat(queue.renew(id, old.runToken(), NOW.plusSeconds(31), LEASE)).isFalse();
        assertThat(queue.renew(id, current.runToken(), NOW.plusSeconds(31), LEASE)).isTrue();
        assertThat(jobs.findById(id).orElseThrow().getLeaseUntil()).isEqualTo(NOW.plusSeconds(61));
    }

    @Test void threeInterruptedAttemptsBecomeFinalFailureWithoutAFourthClaim() {
        UUID id = fixture();
        queue.claim(NOW, LEASE).orElseThrow();
        queue.claim(NOW.plusSeconds(30), LEASE).orElseThrow();
        queue.claim(NOW.plusSeconds(60), LEASE).orElseThrow();
        assertThat(queue.claim(NOW.plusSeconds(90), LEASE)).isEmpty();
        var ended = jobs.findById(id).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(ended.getAttempt()).isEqualTo(3);
        assertThat(ended.getErrorCode()).isEqualTo("WORKER_LEASE_EXPIRED");
        assertThat(ended.getRetryable()).isTrue();
    }

    @Test void retryWaitIsNotClaimedEarlyAndKeepsTheSuccessfulStage() {
        UUID id = fixture(); var first = queue.claim(NOW, LEASE).orElseThrow();
        queue.advance(id, first.runToken(), AnalysisStage.INDEXING, NOW.plusSeconds(1));
        assertThat(queue.fail(id, first.runToken(), "AI_UNAVAILABLE", "잠시 후 다시 시도해 주세요.", true,
                NOW.plusSeconds(2), Duration.ofSeconds(10))).isTrue();
        assertThat(queue.claim(NOW.plusSeconds(11), LEASE)).isEmpty();
        var retry = queue.claim(NOW.plusSeconds(12), LEASE).orElseThrow();
        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(retry.stage()).isEqualTo(AnalysisStage.INDEXING);
    }

    private UUID fixture() {
        return tx.execute(status -> {
            String videoId = UUID.randomUUID().toString().substring(0,11);
            em.persist(new Video(videoId));
            var batch = new AnalysisBatch("queue-test", null, NOW); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), videoId, NOW.plusSeconds(600), NOW); em.persist(item); em.flush();
            var upload = CaptionUpload.issued("queue-test", item.getId(), videoId, "test-bucket", UUID.randomUUID()+".json",
                    "application/json", 100, NOW.plusSeconds(300)); em.persist(upload); em.flush();
            var job = AnalysisJob.queued("queue-test", batch.getId(), item.getId(), videoId, upload.getId(), 7, null, NOW);
            em.persist(job); em.flush(); item.submit(job.getId(), NOW); return job.getId();
        });
    }
}
