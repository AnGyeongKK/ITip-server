package com.gkk.itip.worker.analysis;

import com.gkk.itip.common.storage.GcsAccessException;
import com.gkk.itip.common.storage.GcsObjects;
import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.worker.analysis.ai.SpringAiAnalysisClient;
import com.gkk.itip.worker.analysis.ai.SplitAnalysisFixtures;
import com.gkk.itip.worker.analysis.ai.SplitAnalysisGenerator;
import com.gkk.itip.worker.analysis.caption.CaptionChunker;
import com.gkk.itip.worker.analysis.caption.CaptionParser;
import com.gkk.itip.worker.analysis.output.AnalysisOutputValidator;
import com.gkk.itip.worker.config.AnalysisWorkerProperties;
import com.gkk.itip.worker.config.WorkerExecutionConfiguration;
import com.gkk.itip.worker.config.AnalysisModelConfiguration;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.*;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 모델 JSON은 외부 응답을 대신하는 고정 예시이며 실제 분석 품질의 근거가 아니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.flyway.enabled=true", "itip.worker.analysis-model.model=gpt-6-luna",
        "itip.worker.analysis-model.token-encoding=O200K_BASE", "itip.worker.analysis-model.context-tokens=1050000",
        "itip.worker.scheduling-enabled=false", "itip.worker.heartbeat-delay=PT0.05S"})
@Testcontainers
class AnalysisWorkerTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Test void suppressesTheLibraryLoggerThatCanLogRawCaptions() {
        var logger = LoggerFactory.getLogger("org.springframework.ai.openai.OpenAiChatModel");
        assertThat(logger.isWarnEnabled()).isFalse();
    }

    @Autowired ApplicationContext context;
    @Autowired AnalysisWorker worker;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired AnalysisJobRepository jobs;
    @Autowired AnalysisResultRepository results;
    @Autowired AnalysisRecordRepository records;
    @Autowired AnalysisSectionRepository sections;
    @Autowired MutableClock clock;
    @MockitoSpyBean JobQueue queue;
    @MockitoSpyBean WorkerResultStore store;
    @MockitoSpyBean ExpiredPreparationService preparations;
    @MockitoBean GcsObjects gcs;
    @MockitoBean ChatModel chat;
    @MockitoBean EmbeddingModel embedding;
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final String VIDEO = "dQw4w9WgXcQ";
    private static final String PRIVATE_TEXT = "PRIVATE_CAPTION_자막 원문";
    private static final byte[] CAPTION = ("""
            {"videoId":"dQw4w9WgXcQ","language":"ko","segments":[
              {"startMs":0,"endMs":1000,"text":"%s"},
              {"startMs":1000,"endMs":2000,"text":"원문 근거를 확인합니다."}]}
            """).formatted(PRIVATE_TEXT).getBytes(StandardCharsets.UTF_8);
    private static final String OUTPUT = """
            {"schemaVersion":"1.0","summary":{"oneLine":"근거 검색","keyPoints":["검색"],
             "practicalPoints":[],"mentionedTools":[],"keywords":[],"highlightSectionKeys":["topic-a"]},
             "sections":[{"sectionKey":"topic-a","title":"근거","startSegmentId":"s000001","endSegmentId":"s000002",
              "sourceSegmentIds":["s000001","s000002"],"summary":"원문 검색","subtopics":[],"keywords":[],
              "detail":{"takeaway":"근거 확인","explanation":"원문을 검색합니다.","applicationTips":[],
              "recommendedTools":[],"usageKeywords":[],"examples":[]}}],
             "mindmap":{"rootTitle":"검색","sectionKeys":["topic-a"]}}
            """;

    @BeforeEach void prepare() {
        jdbc.execute("truncate analysis_batches, videos cascade");
        clock.set(NOW);
        reset(gcs, chat, embedding);
        when(gcs.read("test-bucket", "caption.json", 7, 1048576)).thenAnswer(call -> {
            assertOutsideTransaction();
            return CAPTION;
        });
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            assertOutsideTransaction();
            assertThat(MDC.get("jobId")).isNotBlank();
            return SplitAnalysisFixtures.reply(call.getArgument(0, Prompt.class));
        });
        when(embedding.call(any(EmbeddingRequest.class))).thenAnswer(call -> {
            assertOutsideTransaction();
            var request = call.getArgument(0, EmbeddingRequest.class);
            var vectors = new ArrayList<Embedding>();
            for (int index = 0; index < request.getInstructions().size(); index++)
                vectors.add(new Embedding(new float[]{1, 2}, index));
            var metadata = new EmbeddingResponseMetadata(); metadata.setModel("test-model-v1");
            return new EmbeddingResponse(vectors, metadata);
        });
    }

    @Test void runsClaimThroughModelsAndCommitsSectionsVectorsAndExactlyOneRecord() {
        UUID jobId = fixture(3000L, CAPTION.length);
        assertThat(runNext()).isTrue();
        var job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getStage()).isEqualTo(AnalysisStage.FINISHING);
        assertThat(job.getAttempt()).isEqualTo(1);
        assertThat(records.count()).isEqualTo(1);
        assertThat(results.count()).isEqualTo(1);
        assertThat(results.findById(job.getAnalysisId()).orElseThrow().isChatReady()).isTrue();
        var saved = sections.findAllByAnalysisIdOrderByStartMsAscIdAsc(job.getAnalysisId());
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getStartMs()).isZero();
        assertThat(saved.getFirst().getEndMs()).isEqualTo(2000);
        assertThat(jdbc.queryForObject("select text from analysis_chunks", String.class)).contains(PRIVATE_TEXT);
        assertThat(jdbc.queryForObject("select embedding::text from analysis_chunks", String.class)).isEqualTo("[1,2]");
        assertThat(jdbc.queryForObject("select section_ids[1] from analysis_chunks", UUID.class)).isEqualTo(saved.getFirst().getId());
        assertThat(runNext()).isFalse();
        assertThat(MDC.get("jobId")).isNull();
        verify(chat, times(3)).call(any(Prompt.class));
        verify(embedding).call(any(EmbeddingRequest.class));
    }

    @Test void configuredOpenAiModelCompletesAndStoresANewJob() throws Exception {
        var received = new AtomicReference<String>();
        var modelCalls = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            modelCalls.incrementAndGet();
            var wire=context.getBean(JsonMapper.class).readTree(received.get());
            var wireMessages=wire.get("messages");
            var stageInput=context.getBean(JsonMapper.class).readTree(wireMessages.get(wireMessages.size()-1).get("content").asString());
            String json = context.getBean(JsonMapper.class).writeValueAsString(Map.of(
                    "id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-analysis",
                    "choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", context.getBean(JsonMapper.class).writeValueAsString(SplitAnalysisFixtures.payload(stageInput))),
                            "finish_reason", "stop")), "usage", Map.of("prompt_tokens",100,"completion_tokens",100,"total_tokens",200)));
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(AnalysisModelConfiguration.class)
                    .withPropertyValues("itip.worker.analysis-model.enabled=true",
                            "itip.worker.analysis-model.api-key=fixture-only-not-a-secret",
                            "itip.worker.analysis-model.model=fixture-analysis",
                            "itip.worker.analysis-model.token-encoding=O200K_BASE",
                            "itip.worker.analysis-model.context-tokens=1050000",
                            "itip.worker.analysis-model.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .run(application -> {
                        assertThat(application).hasNotFailed();
                        var beans = new StaticListableBeanFactory();
                        beans.addBean("analysis", application.getBean(ChatModel.class));
                        beans.addBean("embedding", embedding);
                        SplitAnalysisGenerator generation;
                        try { generation = new SplitAnalysisGenerator(beans.getBeanProvider(ChatModel.class), context.getBean(JsonMapper.class),
                                context.getBean(com.gkk.itip.worker.analysis.ai.AnalysisSchemas.class), context.getBean(com.gkk.itip.worker.analysis.caption.AnalysisCaptionGroups.class),
                                application.getBean(com.gkk.itip.worker.config.AnalysisGenerationProperties.class)); }
                        catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
                        var ai = new SpringAiAnalysisClient(beans.getBeanProvider(ChatModel.class),
                                beans.getBeanProvider(EmbeddingModel.class), generation, "", 32);
                        UUID id = fixture(3000L, CAPTION.length);
                        assertThat(processor(ai, 1048576).runNext()).isTrue();
                        var job = jobs.findById(id).orElseThrow();
                        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
                        assertThat(results.findById(job.getAnalysisId()).orElseThrow().isChatReady()).isTrue();
                        assertThat(records.count()).isEqualTo(1);
                        assertThat(sections.findAllByAnalysisIdOrderByStartMsAscIdAsc(job.getAnalysisId())).hasSize(1);
                        assertThat(jdbc.queryForObject("select count(*) from analysis_chunks", Integer.class)).isEqualTo(1);
                        assertThat(received.get()).contains("fixture-analysis", "g000001");
                        assertThat(modelCalls.get()).isEqualTo(3);
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test void rejectsBadCaptionBeforeCallingEitherModelAndReportsClaimedEvenOnFailure() {
        UUID id = fixture(3000L, 1);
        when(gcs.read(anyString(), anyString(), anyLong(), anyLong())).thenReturn(new byte[]{'x'});
        assertThat(runNext()).isTrue();
        assertFailure(id, "CAPTION_INVALID_FORMAT", false, JobStatus.FAILED);
        verifyNoInteractions(chat, embedding);
        assertThat(results.count()).isZero();
        assertThat(MDC.get("jobId")).isNull();
    }

    @Test void requiresRealVideoDurationAndDoesNotReadAnObjectWithUnknownMetadata() {
        UUID id = fixture(null, CAPTION.length);
        assertThat(runNext()).isTrue();
        assertFailure(id, "VIDEO_METADATA_REQUIRED", false, JobStatus.FAILED);
        verifyNoInteractions(gcs, chat, embedding);
    }

    @Test void refusesDeclaredSizeMismatchBeforeModelGeneration() {
        UUID id = fixture(3000L, CAPTION.length + 1);
        runNext();
        assertFailure(id, "CAPTION_SIZE_MISMATCH", false, JobStatus.FAILED);
        verifyNoInteractions(chat, embedding);
    }

    @Test void retriesTransientModelFailureOnlyWhenDueAndStopsAfterThreeClaims() {
        UUID id = fixture(3000L, CAPTION.length);
        when(chat.call(any(Prompt.class))).thenThrow(new TransientAiException("PRIVATE_PROVIDER_RAW"));
        runNext();
        assertFailure(id, "AI_UNAVAILABLE", true, JobStatus.RETRY_WAIT);
        assertThat(runNext()).isFalse();
        clock.set(NOW.plusSeconds(5));
        assertThat(runNext()).isTrue();
        assertFailure(id, "AI_UNAVAILABLE", true, JobStatus.RETRY_WAIT);
        clock.set(NOW.plusSeconds(10));
        assertThat(runNext()).isTrue();
        assertFailure(id, "AI_UNAVAILABLE", true, JobStatus.FAILED);
        assertThat(jobs.findById(id).orElseThrow().getAttempt()).isEqualTo(3);
        clock.set(NOW.plusSeconds(100));
        assertThat(runNext()).isFalse();
        verify(chat, times(3)).call(any(Prompt.class));
        verifyNoInteractions(embedding);
    }

    @Test void indexRetryPreservesServerIdsAndDraftJsonAndRepeatsOnlySourceAndEmbedding() {
        UUID id = fixture(3000L, CAPTION.length);
        when(embedding.call(any(EmbeddingRequest.class))).thenThrow(new TransientAiException("PRIVATE_PROVIDER_RAW"));
        runNext();
        var job = jobs.findById(id).orElseThrow();
        UUID analysisId = job.getAnalysisId();
        String original = results.findById(analysisId).orElseThrow().getResultJson();
        UUID sectionId = sections.findAllByAnalysisIdOrderByStartMsAscIdAsc(analysisId).getFirst().getId();
        assertFailure(id, "AI_UNAVAILABLE", true, JobStatus.RETRY_WAIT);
        reset(embedding);
        var metadata = new EmbeddingResponseMetadata(); metadata.setModel("test-model-v1");
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(
                new EmbeddingResponse(List.of(new Embedding(new float[]{1, 2}, 0)), metadata));
        clock.set(NOW.plusSeconds(5));
        runNext();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(jobs.findById(id).orElseThrow().getAnalysisId()).isEqualTo(analysisId);
        assertThat(results.findById(analysisId).orElseThrow().getResultJson()).isEqualTo(original);
        assertThat(sections.findAllByAnalysisIdOrderByStartMsAscIdAsc(analysisId).getFirst().getId()).isEqualTo(sectionId);
        assertThat(records.count()).isEqualTo(1);
        verify(chat, times(3)).call(any(Prompt.class));
        verify(gcs, times(2)).read("test-bucket", "caption.json", 7, 1048576);
    }

    @Test void completionRetryUsesTheCommittedIndexWithoutAnyExternalRecall() {
        UUID id = fixture(3000L, CAPTION.length);
        // 잠금 실패는 DB 경계에서 한 번 주입한다. draft/index/재시도/완료 저장은 실제 PostgreSQL이다.
        doThrow(new CannotAcquireLockException("PRIVATE_DB_RAW")).doCallRealMethod()
                .when(store).complete(any(), any(), any());
        runNext();
        assertFailure(id, "WORKER_DATABASE_UNAVAILABLE", true, JobStatus.RETRY_WAIT);
        assertThat(jdbc.queryForObject("select count(*) from analysis_chunks", Integer.class)).isEqualTo(1);
        assertThat(records.count()).isZero();
        clock.set(NOW.plusSeconds(5));
        runNext();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(records.count()).isEqualTo(1);
        verify(gcs).read("test-bucket", "caption.json", 7, 1048576);
        verify(chat, times(3)).call(any(Prompt.class));
        verify(embedding).call(any(EmbeddingRequest.class));
    }

    @Test void oldModelResponseCannotOverwriteANewClaimAfterLeaseExpiry() {
        UUID id = fixture(3000L, CAPTION.length);
        AtomicReference<JobQueue.Claim> newer = new AtomicReference<>();
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            clock.set(NOW.plusSeconds(121));
            if (newer.get() == null) newer.set(queue.claim(clock.instant(), Duration.ofMinutes(2)).orElseThrow());
            return SplitAnalysisFixtures.reply(call.getArgument(0, Prompt.class));
        });
        runNext();
        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getRunToken()).isEqualTo(newer.get().runToken());
        assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.getAttempt()).isEqualTo(2);
        assertThat(job.getErrorCode()).isNull();
        assertThat(results.count()).isZero();
        verifyNoInteractions(embedding);
        assertThat(MDC.get("jobId")).isNull();
    }

    @Test void heartbeatRenewsWhileModelIsBlockedAndItsScheduledTaskIsCancelledAfterCompletion() throws Exception {
        UUID id = fixture(3000L, CAPTION.length);
        var renewed = new CountDownLatch(1);
        var callbackSeen = new CountDownLatch(1);
        doAnswer(call -> {
            try {
                assertThat(MDC.get("jobId")).isEqualTo(id.toString());
                boolean value = (boolean)call.callRealMethod();
                if (value && call.getArgument(2, Instant.class).isAfter(NOW))
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { renewed.countDown(); }
                    });
                return value;
            } finally { callbackSeen.countDown(); }
        }).when(queue).renew(any(), any(), any(), any());
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            assertOutsideTransaction();
            clock.set(NOW.plusSeconds(60));
            assertThat(renewed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.findById(id).orElseThrow().getLeaseUntil()).isEqualTo(NOW.plusSeconds(180));
            return SplitAnalysisFixtures.reply(call.getArgument(0, Prompt.class));
        });
        assertThat(runNext()).isTrue();
        assertThat(callbackSeen.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).isEmpty();
        assertThat(MDC.get("jobId")).isNull();
    }

    @Test void expiresOnlyUnsubmittedPreparationBeforeProcessingTheAdmittedJob() {
        UUID id = fixture(3000L, CAPTION.length);
        AtomicReference<UUID> expired = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            var batch = new AnalysisBatch("other-owner", null, NOW.minusSeconds(600)); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), VIDEO, NOW.minusSeconds(1), NOW.minusSeconds(600));
            em.persist(item); expired.set(item.getId());
        });
        runNext();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(jdbc.queryForObject("select status from analysis_batch_items where id = ?", String.class, expired.get())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_code from analysis_batch_items where id = ?", String.class, expired.get())).isEqualTo("CAPTION_UPLOAD_EXPIRED");
    }

    @ParameterizedTest @EnumSource(GcsAccessException.Reason.class)
    void translatesStorageFailuresWithoutRetryingPermanentObjects(GcsAccessException.Reason reason) {
        UUID id = fixture(3000L, CAPTION.length);
        when(gcs.read(anyString(), anyString(), anyLong(), anyLong())).thenThrow(new GcsAccessException(reason, true));
        runNext();
        String code = switch (reason) {
            case OBJECT_NOT_FOUND, OBJECT_VERSION_CHANGED -> "CAPTION_REUPLOAD_REQUIRED";
            default -> reason.name();
        };
        boolean retryable = reason == GcsAccessException.Reason.STORAGE_UNAVAILABLE;
        assertFailure(id, code, retryable, retryable ? JobStatus.RETRY_WAIT : JobStatus.FAILED);
        verifyNoInteractions(chat, embedding);
    }

    @Test void invalidModelReferencesNeverCreateADraftOrIndex() {
        UUID id = fixture(3000L, CAPTION.length);
        when(chat.call(any(Prompt.class))).thenAnswer(call -> SplitAnalysisFixtures.replyWithInvalidDetail(call.getArgument(0, Prompt.class)));
        runNext();
        assertFailure(id, "AI_INVALID_RESPONSE", false, JobStatus.FAILED);
        assertThat(results.count()).isZero();
        verifyNoInteractions(embedding);
    }

    @Test void invalidVectorsFailPermanentlyWithoutMarkingTheDraftReady() {
        UUID id = fixture(3000L, CAPTION.length);
        var metadata = new EmbeddingResponseMetadata(); metadata.setModel("test-model-v1");
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(
                new EmbeddingResponse(List.of(new Embedding(new float[]{Float.NaN, 2}, 0)), metadata));
        runNext();
        assertFailure(id, "WORKER_DATA_INVALID", false, JobStatus.FAILED);
        assertThat(records.count()).isZero();
        assertThat(results.findAll().getFirst().isChatReady()).isFalse();
    }

    @Test void neverPublishesSensitiveRuntimeMessagesInJobErrorsOrWorkerLogs() {
        UUID id = fixture(3000L, CAPTION.length);
        var logger = (ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("com.gkk.itip.worker");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            when(gcs.read(anyString(), anyString(), anyLong(), anyLong())).thenThrow(
                    new RuntimeException(PRIVATE_TEXT + " PRIVATE_PROVIDER_RAW https://signed.example/token"));
            runNext();
            assertFailure(id, "WORKER_ERROR", false, JobStatus.FAILED);
            assertThat(jobs.findById(id).orElseThrow().getErrorMessage()).doesNotContain(PRIVATE_TEXT, "PRIVATE_PROVIDER_RAW", "signed.example");
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_TEXT, "PRIVATE_PROVIDER_RAW", "signed.example");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains(id.toString(), "WORKER_ERROR"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void actualSdkEmptyChoicesCannotLogTheOriginalPrompt() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework.ai.openai.OpenAiChatModel");
        assertThat(logger.getEffectiveLevel()).isEqualTo(ch.qos.logback.classic.Level.OFF);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body="{\"id\":\"chatcmpl-empty\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-6-luna\",\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var client=com.gkk.itip.ai.OpenAiClients.create("fixture-not-a-real-key","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",Duration.ofSeconds(10),Duration.ofSeconds(10));
        try {
            var actual=org.springframework.ai.openai.OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async())
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder().maxRetries(0).build()).build();
            var beans=new StaticListableBeanFactory();beans.addBean("actual",actual);
            var generator=new SplitAnalysisGenerator(beans.getBeanProvider(ChatModel.class),context.getBean(JsonMapper.class),
                context.getBean(com.gkk.itip.worker.analysis.ai.AnalysisSchemas.class),context.getBean(com.gkk.itip.worker.analysis.caption.AnalysisCaptionGroups.class),
                context.getBean(com.gkk.itip.worker.config.AnalysisGenerationProperties.class));
            var captions=context.getBean(CaptionParser.class).parse(CAPTION,VIDEO,3000,1048576);
            assertThatThrownBy(() -> generator.generate(captions,3000,PRIVATE_TEXT)).isInstanceOf(com.gkk.itip.worker.analysis.ai.AnalysisAiException.class);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(appender.list).isEmpty();
        } finally {client.close();server.stop(0);logger.detachAppender(appender);appender.stop();}
    }

    @Test void eachDatabaseWriteUsesTheClockAfterItsExternalWait() {
        UUID id = fixture(3000L, CAPTION.length);
        when(gcs.read(anyString(), anyString(), anyLong(), anyLong())).thenAnswer(call -> {
            clock.set(NOW.plusSeconds(1)); return CAPTION;
        });
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            clock.set(NOW.plusSeconds(2)); return SplitAnalysisFixtures.reply(call.getArgument(0, Prompt.class));
        });
        when(embedding.call(any(EmbeddingRequest.class))).thenAnswer(call -> {
            clock.set(NOW.plusSeconds(3));
            var metadata = new EmbeddingResponseMetadata(); metadata.setModel("test-model-v1");
            return new EmbeddingResponse(List.of(new Embedding(new float[]{1}, 0)), metadata);
        });
        runNext();
        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getUpdatedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(results.findById(job.getAnalysisId()).orElseThrow().getCreatedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(records.findAll().getFirst().getAnalyzedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test void preparationFailureForOneOwnerDoesNotBlockOtherOwnersOrTheAdmittedJob() {
        UUID id = fixture(3000L, CAPTION.length);
        tx.executeWithoutResult(status -> {
            for (String owner : List.of("cleanup-failing-owner", "cleanup-working-owner")) {
                var batch = new AnalysisBatch(owner, null, NOW.minusSeconds(60)); em.persist(batch);
                em.persist(AnalysisBatchItem.collectCaptions(batch.getId(), VIDEO, NOW.minusSeconds(1), NOW.minusSeconds(60)));
            }
        });
        doThrow(new CannotAcquireLockException("PRIVATE_CLEANUP_RAW"))
                .when(preparations).expire(eq("cleanup-failing-owner"), any());
        runNext();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(jdbc.queryForObject("""
                select i.status from analysis_batch_items i join analysis_batches b on b.id=i.batch_id
                where b.owner_id='cleanup-working-owner'
                """, String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                select i.status from analysis_batch_items i join analysis_batches b on b.id=i.batch_id
                where b.owner_id='cleanup-failing-owner'
                """, String.class)).isEqualTo("AWAITING_UPLOAD");
    }

    @Test void disabledSchedulingLeavesTheProcessorAvailableWithoutAnAutomaticPoll() {
        assertThat(context.getBeansOfType(AnalysisScheduler.class)).isEmpty();
        var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getRemoveOnCancelPolicy()).isTrue();
        assertThat(runNext()).isFalse();
    }

    @Test void realPeriodicPollingContinuesAfterRuntimeFailureAndUsesPlatformThreads() {
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var polledAgain = new CountDownLatch(1);
        var processor = mock(AnalysisWorker.class);
        when(processor.runNext()).thenAnswer(call -> {
            assertThat(Thread.currentThread().isVirtual()).isFalse();
            if (first.getAndSet(false)) throw new RuntimeException("PRIVATE_POLL_RAW");
            polledAgain.countDown(); return false;
        });
        new ApplicationContextRunner().withUserConfiguration(WorkerExecutionConfiguration.class, AnalysisScheduler.class)
                .withBean(AnalysisWorker.class, () -> processor)
                .withPropertyValues("itip.worker.poll-delay=PT0.01S")
                .run(application -> {
                    assertThat(application).hasSingleBean(AnalysisScheduler.class);
                    assertThat(polledAgain.await(5, TimeUnit.SECONDS)).isTrue();
                });
    }

    @Test void rejectsInvalidWorkerTimingAndChunkSettingsBeforePolling() {
        for (String setting : List.of("itip.worker.poll-delay=PT0S", "itip.worker.lease-duration=PT0S",
                "itip.worker.heartbeat-delay=PT0S", "itip.worker.heartbeat-delay=PT2M",
                "itip.worker.retry-delay=-PT1S", "itip.worker.chunk-code-points=0")) {
            new ApplicationContextRunner().withUserConfiguration(WorkerExecutionConfiguration.class)
                    .withPropertyValues(setting).run(application -> assertThat(application).hasFailed());
        }
        new ApplicationContextRunner().withUserConfiguration(WorkerExecutionConfiguration.class)
                .withPropertyValues("itip.worker.retry-delay=PT0S")
                .run(application -> assertThat(application).hasNotFailed());
    }

    @Test void missingModelBeansFailExplicitlyAndNeverCreateAFakeCompletion() {
        UUID id = fixture(3000L, CAPTION.length);
        var empty = new StaticListableBeanFactory();
        var ai = new SpringAiAnalysisClient(empty.getBeanProvider(ChatModel.class), empty.getBeanProvider(EmbeddingModel.class),
                context.getBean(SplitAnalysisGenerator.class), "", 32);
        assertThat(processor(ai, 1048576).runNext()).isTrue();
        assertFailure(id, "AI_NOT_CONFIGURED", false, JobStatus.FAILED);
        assertThat(results.count()).isZero();
        assertThat(records.count()).isZero();
        verifyNoInteractions(chat, embedding);
    }

    @ParameterizedTest @ValueSource(longs = {-1, 0, 2147483647L, 2147483648L})
    void rejectsCaptionByteLimitsThatCannotBeReadIntoABoundedByteArray(long limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> processor(context.getBean(SpringAiAnalysisClient.class), limit));
    }

    @Test void failedFailureRecordingLeavesTheJobForLeaseRecoveryAndCancelsHeartbeat() {
        UUID id = fixture(3000L, CAPTION.length);
        when(gcs.read(anyString(), anyString(), anyLong(), anyLong())).thenThrow(new RuntimeException("PRIVATE_RAW"));
        doThrow(new DataAccessResourceFailureException("PRIVATE_DATABASE_RAW")).when(queue)
                .fail(any(), any(), anyString(), anyString(), anyBoolean(), any(), any());
        assertThatThrownBy(this::runNext).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(jobs.findById(id).orElseThrow().getErrorCode()).isNull();
        assertThat(context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getQueue()).isEmpty();
        assertThat(MDC.get("jobId")).isNull();
        clock.set(NOW.plusSeconds(121));
        doReturn(CAPTION).when(gcs).read(anyString(), anyString(), anyLong(), anyLong());
        assertThat(runNext()).isTrue();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(records.count()).isEqualTo(1);
    }

    @Test void oneHeartbeatDatabaseFailureDoesNotStopLaterRenewalsOrFailTheJob() throws Exception {
        UUID id = fixture(3000L, CAPTION.length);
        var failed = new CountDownLatch(1);
        var succeeded = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            if (first.getAndSet(false)) {
                failed.countDown();
                throw new CannotAcquireLockException("PRIVATE_HEARTBEAT_RAW");
            }
            boolean value = (boolean)call.callRealMethod();
            if (value) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { succeeded.countDown(); }
            });
            return value;
        }).when(queue).renew(any(), any(), any(), any());
        when(chat.call(any(Prompt.class))).thenAnswer(call -> {
            assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(succeeded.await(5, TimeUnit.SECONDS)).isTrue();
            return SplitAnalysisFixtures.reply(call.getArgument(0, Prompt.class));
        });
        runNext();
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(records.count()).isEqualTo(1);
    }

    private AnalysisWorker processor(SpringAiAnalysisClient ai, long maxBytes) {
        return new AnalysisWorker(preparations, queue, store, gcs, context.getBean(CaptionParser.class),
                context.getBean(CaptionChunker.class), ai, context.getBean(AnalysisOutputValidator.class), clock,
                context.getBean(ThreadPoolTaskScheduler.class), context.getBean(AnalysisWorkerProperties.class), maxBytes);
    }

    private boolean runNext() {
        return worker.runNext();
    }

    private UUID fixture(Long durationMs, long size) {
        return tx.execute(status -> {
            var video = new Video(VIDEO);
            if (durationMs != null) video.updateMetadata(null, null, null, null, durationMs, null);
            em.persist(video);
            var batch = new AnalysisBatch("worker-test", null, NOW); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), VIDEO, NOW.plusSeconds(600), NOW);
            em.persist(item); em.flush();
            var upload = CaptionUpload.issued("worker-test", item.getId(), VIDEO, "test-bucket", "caption.json", "application/json", size, NOW.plusSeconds(600));
            upload.consume(7); em.persist(upload); em.flush();
            var job = AnalysisJob.queued("worker-test", batch.getId(), item.getId(), VIDEO, upload.getId(), 7, null, NOW);
            em.persist(job); em.flush(); item.submit(job.getId(), NOW);
            return job.getId();
        });
    }

    private void assertFailure(UUID id, String code, boolean retryable, JobStatus status) {
        var job = jobs.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(status);
        assertThat(job.getErrorCode()).isEqualTo(code);
        assertThat(job.getRetryable()).isEqualTo(retryable);
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("STOP").build())));
    }

    private static void assertOutsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
