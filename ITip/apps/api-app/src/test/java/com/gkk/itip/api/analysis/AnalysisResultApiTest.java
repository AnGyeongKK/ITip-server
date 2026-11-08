package com.gkk.itip.api.analysis;

import com.gkk.itip.common.storage.GcsObjects;
import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.analysis.result.ResultDocument;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.video.Video;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "itip.demo.owner-id=demo-user")
@AutoConfigureMockMvc
@Testcontainers
class AnalysisResultApiTest {
    static final String OWNER = "demo-user", VIDEO = "dQw4w9WgXcQ", BASE = "/api/v1/analyses/";
    static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    static final UUID FIRST = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID SECOND = UUID.fromString("22222222-2222-4222-8222-222222222222");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @MockitoBean GcsObjects gcs;

    @BeforeEach void clear() {
        jdbc.execute("truncate videos, analysis_batches, idempotency_requests cascade");
        tx.executeWithoutResult(status -> {
            var video = new Video(VIDEO);
            video.updateMetadata("RAG 원본 영상", "예시 채널", "https://example.com/channel.jpg",
                    "https://example.com/thumbnail.jpg", 120_000L, NOW.minusSeconds(86_400));
            em.persist(video);
        });
    }

    @Test void returnsSummaryKeywordsAndOrderedMindmapWithoutSectionDetails() throws Exception {
        var f = fixture(OWNER, true);
        result(f).andExpect(status().isOk()).andExpect(jsonPath("code").value("OK"))
                .andExpect(jsonPath("message").isString())
                .andExpect(jsonPath("data.analysisId").value(f.analysisId().toString()))
                .andExpect(jsonPath("data.recordId").value(f.recordId().toString()))
                .andExpect(jsonPath("data.status").value("COMPLETED"))
                .andExpect(jsonPath("data.schemaVersion").value("1.0"))
                .andExpect(jsonPath("data.chatReady").value(true))
                .andExpect(jsonPath("data.analyzedAt").value("2026-11-08T00:00:10Z"))
                .andExpect(jsonPath("data.video.title").value("RAG 원본 영상"))
                .andExpect(jsonPath("data.video.durationMs").value(120000))
                .andExpect(jsonPath("data.video.publishedAt").value("2026-11-07T00:00:00Z"))
                .andExpect(jsonPath("data.summary.oneLine").value("영상 전체 한 줄 요약"))
                .andExpect(jsonPath("data.summary.practicalPoints[0].origin").value("AI_SUGGESTION"))
                .andExpect(jsonPath("data.summary.keywords[0].keywordId").value("33333333-3333-4333-8333-333333333333"))
                .andExpect(jsonPath("data.summary.highlightSectionIds[0]").value(SECOND.toString()))
                .andExpect(jsonPath("data.sections[0].sectionId").value(FIRST.toString()))
                .andExpect(jsonPath("data.sections[0].subtopics[0]").value("근거 확인"))
                .andExpect(jsonPath("data.sections[0].startMs").value(1500))
                .andExpect(jsonPath("data.sections[0].detail").doesNotExist())
                .andExpect(jsonPath("data.mindmap.rootTitle").value("근거 검색"))
                .andExpect(jsonPath("data.mindmap.sectionIds[0]").value(SECOND.toString()))
                .andExpect(jsonPath("data.mindmap.sectionIds[1]").value(FIRST.toString()));
    }

    @Test void keepsUnknownVideoMetadataNullAndEmptyOptionalArrays() throws Exception {
        var f = fixture(OWNER, true);
        jdbc.update("update videos set channel_image_url=null, thumbnail_url=null, published_at=null, duration_ms=null");
        JsonNode data = data(result(f).andExpect(status().isOk()));
        for (String field : List.of("channelImageUrl", "thumbnailUrl", "publishedAt", "durationMs")) {
            assertThat(data.path("video").has(field)).isTrue();
            assertThat(data.path("video").path(field).isNull()).isTrue();
        }
        assertThat(data.path("summary").path("mentionedTools").size()).isZero();
        assertThat(data.path("sections").path(1).path("subtopics").size()).isZero();
    }

    @Test void sortsSectionListByTimeWhilePreservingMindmapAndHighlightOrder() throws Exception {
        var f = fixture(OWNER, true);
        var document = document();
        corrupt(f, mapper.writeValueAsString(new ResultDocument(document.schemaVersion(), document.summary(),
                document.sections().reversed(), document.mindmap())));
        result(f).andExpect(status().isOk())
                .andExpect(jsonPath("data.sections[0].sectionId").value(FIRST.toString()))
                .andExpect(jsonPath("data.sections[1].sectionId").value(SECOND.toString()))
                .andExpect(jsonPath("data.mindmap.sectionIds[0]").value(SECOND.toString()))
                .andExpect(jsonPath("data.summary.highlightSectionIds[0]").value(SECOND.toString()));
    }

    @Test void repeatedReadsDoNotChangeResultsRecordsJobsOrCallStorage() throws Exception {
        var f = fixture(OWNER, true);
        var beforeResult = jdbc.queryForMap("select * from analysis_results where id=?", f.analysisId());
        var beforeRecord = jdbc.queryForMap("select * from analysis_records where id=?", f.recordId());
        var beforeJob = jdbc.queryForMap("select * from analysis_jobs where id=?", f.jobId());
        var first = data(result(f).andExpect(status().isOk()));
        assertThat(data(result(f).andExpect(status().isOk()))).isEqualTo(first);
        assertThat(jdbc.queryForMap("select * from analysis_results where id=?", f.analysisId())).isEqualTo(beforeResult);
        assertThat(jdbc.queryForMap("select * from analysis_records where id=?", f.recordId())).isEqualTo(beforeRecord);
        assertThat(jdbc.queryForMap("select * from analysis_jobs where id=?", f.jobId())).isEqualTo(beforeJob);
        verifyNoInteractions(gcs);
    }

    @Test void missingOtherOwnerAndDeletedRecordsAreIndistinguishableBeforeJsonParsing() throws Exception {
        error(mvc.perform(get(BASE + UUID.randomUUID())), 404, "RESOURCE_NOT_FOUND");
        var other = fixture("another-user", true);
        corrupt(other, "{private-broken-json");
        error(mvc.perform(get(BASE + other.analysisId()).header("X-User-Id", "another-user")), 404, "RESOURCE_NOT_FOUND");
        var mine = fixture(OWNER, true);
        jdbc.update("delete from analysis_records where id=?", mine.recordId());
        corrupt(mine, "{private-broken-json");
        error(result(mine), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void draftIsNotExposedAsACompletedResult() throws Exception {
        var f = fixture(OWNER, false);
        corrupt(f, "{private-broken-json");
        error(result(f), 409, "ANALYSIS_NOT_READY");
    }

    @Test void rejectsMalformedAnalysisId() throws Exception {
        error(mvc.perform(get(BASE + "not-a-uuid")), 400, "INVALID_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{private-broken-json", "null", "{}", "{\"schemaVersion\":\"2.0\"}"})
    void corruptOrUnsupportedStoredDocumentsReturnSafeServerErrors(String invalid) throws Exception {
        var f = fixture(OWNER, true);
        corrupt(f, invalid);
        error(result(f), 500, "INTERNAL_ERROR");
    }

    @Test void rejectsBrokenSectionReferencesAndMissingRequiredContent() throws Exception {
        var f = fixture(OWNER, true);
        String valid = jdbc.queryForObject("select result_json from analysis_results where id=?", String.class, f.analysisId());
        for (String invalid : List.of(
                valid.replace("\"rootTitle\":\"근거 검색\"", "\"rootTitle\":null"),
                valid.replace("\"keyPoints\":[\"원문 검색\"]", "\"keyPoints\":null"),
                valid.replace("\"highlightSectionIds\":[\"" + SECOND + "\"]", "\"highlightSectionIds\":[\"" + UUID.randomUUID() + "\"]"))) {
            assertThat(invalid).isNotEqualTo(valid);
            corrupt(f, invalid);
            error(result(f), 500, "INTERNAL_ERROR");
        }
    }

    private ResultActions result(Fixture f) throws Exception { return mvc.perform(get(BASE + f.analysisId())); }
    private JsonNode data(ResultActions action) throws Exception {
        return mapper.readTree(action.andReturn().getResponse().getContentAsString()).path("data");
    }
    private void error(ResultActions action, int status, String code) throws Exception {
        action.andExpect(status().is(status)).andExpect(jsonPath("code").value(code))
                .andExpect(jsonPath("message").isString()).andExpect(jsonPath("data").doesNotExist())
                .andExpect(header().exists("X-Request-Id"));
        assertThat(action.andReturn().getResponse().getContentAsString()).doesNotContain("private-broken-json", "result_json");
    }
    private void corrupt(Fixture f, String json) {
        jdbc.update("update analysis_results set result_json=? where id=?", json, f.analysisId());
    }

    private record Fixture(UUID analysisId, UUID recordId, UUID jobId) {}
    private Fixture fixture(String owner, boolean ready) {
        return tx.execute(status -> {
            var batch = new AnalysisBatch(owner, null, NOW); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), VIDEO, NOW.plusSeconds(1800), NOW); em.persist(item);
            var upload = CaptionUpload.issued(owner, item.getId(), VIDEO, "test-bucket", UUID.randomUUID() + ".json",
                    "application/json", 100, NOW.plusSeconds(600)); em.persist(upload); upload.consume(7);
            var job = AnalysisJob.queued(owner, batch.getId(), item.getId(), VIDEO, upload.getId(), 7, null, NOW); em.persist(job);
            // The persisted JSON is the Worker output boundary; GET must not regenerate its IDs or contents.
            var result = AnalysisResult.draft(UUID.randomUUID(), owner, VIDEO, job.getId(), mapper.writeValueAsString(document()), NOW);
            em.persist(result);
            UUID recordId = null;
            if (ready) {
                result.markReady(NOW.plusSeconds(10));
                var record = AnalysisRecord.fromCompleted(result); em.persist(record); recordId = record.getId();
            }
            em.flush();
            // A source job fixture is enough for read APIs; Worker completion behavior has separate integration tests.
            return new Fixture(result.getId(), recordId, job.getId());
        });
    }
    private ResultDocument document() {
        var keyword = new ResultDocument.Keyword("33333333-3333-4333-8333-333333333333", "RAG");
        var detail = new ResultDocument.Detail("구간 핵심", "구간 설명",
                List.of(new ResultDocument.TextPoint("실무 팁", "AI_SUGGESTION")),
                List.of(new ResultDocument.RecommendedTool("Spring AI", "검색 연동", "AI_SUGGESTION")),
                List.of("검색"), List.of(new ResultDocument.TextPoint("원문 예시", "SOURCE")));
        return new ResultDocument("1.0", new ResultDocument.Summary("영상 전체 한 줄 요약", List.of("원문 검색"),
                List.of(new ResultDocument.TextPoint("근거를 확인하세요", "AI_SUGGESTION")), List.of(), List.of(keyword), List.of(SECOND.toString())),
                List.of(new ResultDocument.Section(FIRST.toString(), "검색과 답변", 1500, 60000, "첫 구간 요약", List.of("근거 확인"), List.of(keyword), detail),
                        new ResultDocument.Section(SECOND.toString(), "실무 적용", 60000, 120000, "둘째 구간 요약", List.of(), List.of(), detail)),
                new ResultDocument.Mindmap("근거 검색", List.of(SECOND.toString(), FIRST.toString())));
    }
}
