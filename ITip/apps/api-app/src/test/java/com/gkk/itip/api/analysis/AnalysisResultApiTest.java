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
import org.springframework.http.MediaType;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "itip.demo.owner-id=demo-user")
@AutoConfigureMockMvc
@Testcontainers
class AnalysisResultApiTest {
    private static final String FOLDERS = "/api/v1/folders";
    private static final String RECORDS = "/api/v1/records";
    private static final String RECORD_KEYWORDS = "/api/v1/record-keywords";
    private static final String PLAYLIST_GROUPS = "/api/v1/playlist-groups";
    private static final String HOME = "/api/v1/users/me/home";
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
        jdbc.execute("truncate videos, analysis_batches, idempotency_requests, folders, youtube_playlist_metadata cascade");
        tx.executeWithoutResult(status -> {
            var video = new Video(VIDEO);
            video.updateMetadata("RAG 원본 영상", "예시 채널", "https://example.com/channel.jpg",
                    "https://example.com/thumbnail.jpg", 120_000L, NOW.minusSeconds(86_400));
            em.persist(video);
        });
    }

    @Test void searchesAndCountsOnlyLatestOwnedCompletedRecordsInPostgresql() throws Exception {
        String otherVideo = "9bZkp7q19f0";
        String foreignVideo = "abcdefghijk";
        String keyword = "33333333-3333-4333-8333-333333333333";
        tx.executeWithoutResult(status -> {
            em.persist(new Video(otherVideo));
            em.persist(new Video(foreignVideo));
        });
        var older = fixture(OWNER, true);
        jdbc.update("update analysis_records set analyzed_at=? where id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(10)), older.recordId());
        var firstVideo = fixture(OWNER, true);
        var secondVideo = fixture(OWNER, true, otherVideo);
        fixture("another-owner", true, foreignVideo);
        fixture(OWNER, false, otherVideo);

        var expected = java.util.stream.Stream.of(firstVideo, secondVideo)
                .map(f -> f.recordId().toString()).sorted(java.util.Comparator.reverseOrder()).toList();
        String records = "/api/v1/records";
        var firstPage = data(mvc.perform(get(records).param("q", "RAG").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].recordId").value(expected.getFirst()))
                .andExpect(jsonPath("data.matchedCount").value(2))
                .andExpect(jsonPath("data.hasNext").value(true)));
        mvc.perform(get(records).param("q", "RAG").param("limit", "1")
                        .param("cursor", firstPage.path("nextCursor").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].recordId").value(expected.getLast()))
                .andExpect(jsonPath("data.hasNext").value(false))
                .andExpect(jsonPath("data.matchedCount").value(2));
        mvc.perform(get("/api/v1/record-keywords").param("q", "RAG").param("keywordIds", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.matchedCount").value(2))
                .andExpect(jsonPath("data.keywords.length()").value(1))
                .andExpect(jsonPath("data.keywords[0].recordCount").value(2))
                .andExpect(jsonPath("data.keywords[0].selected").value(true));
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
    @Test void returnsMatchingSectionDetailsAndServerBuiltPlaybackPosition() throws Exception {
        var f = fixture(OWNER, true);
        var listed = data(result(f).andExpect(status().isOk())).path("sections").path(0);
        var response = section(f, FIRST).andExpect(status().isOk())
                .andExpect(jsonPath("data.analysisId").value(f.analysisId().toString()))
                .andExpect(jsonPath("data.recordId").value(f.recordId().toString()))
                .andExpect(jsonPath("data.sectionId").value(FIRST.toString()))
                .andExpect(jsonPath("data.startMs").value(1500))
                .andExpect(jsonPath("data.endMs").value(60000))
                .andExpect(jsonPath("data.videoId").value(VIDEO))
                .andExpect(jsonPath("data.originalUrl").value("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
                .andExpect(jsonPath("data.detail.takeaway").value("구간 핵심"))
                .andExpect(jsonPath("data.detail.explanation").value("구간 설명"))
                .andExpect(jsonPath("data.detail.applicationTips[0].origin").value("AI_SUGGESTION"))
                .andExpect(jsonPath("data.detail.recommendedTools[0].name").value("Spring AI"))
                .andExpect(jsonPath("data.detail.examples[0].origin").value("SOURCE"))
                .andExpect(jsonPath("data.video.title").value("RAG 원본 영상"))
                .andExpect(jsonPath("data.videoOneLine").value("영상 전체 한 줄 요약"));
        JsonNode detail = data(response);
        for (String field : List.of("sectionId", "title", "startMs", "endMs", "summary", "subtopics", "keywords"))
            assertThat(detail.path(field)).isEqualTo(listed.path(field));
        assertThat(data(section(f, FIRST).andExpect(status().isOk()))).isEqualTo(detail);
        verifyNoInteractions(gcs);
    }

    @Test void sectionCanBeOpenedDirectlyWithUnknownVideoMetadata() throws Exception {
        var f = fixture(OWNER, true);
        jdbc.update("update videos set channel_image_url=null, thumbnail_url=null, published_at=null, duration_ms=null");
        JsonNode detail = data(section(f, SECOND).andExpect(status().isOk())
                .andExpect(jsonPath("data.originalUrl").value("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=60"))
                .andExpect(jsonPath("data.subtopics").isEmpty()).andExpect(jsonPath("data.keywords").isEmpty()));
        assertThat(detail.path("video").path("durationMs").isNull()).isTrue();
        assertThat(detail.path("videoOneLine").asString()).isEqualTo("영상 전체 한 줄 요약");
    }

    @Test void rejectsOtherAnalysisSectionEvenWhenTheOtherResultIsVisible() throws Exception {
        var f = fixture(OWNER, true);
        var other = fixture(OWNER, true);
        UUID anotherSection = UUID.randomUUID();
        String otherJson = jdbc.queryForObject("select result_json from analysis_results where id=?", String.class, other.analysisId());
        corrupt(other, otherJson.replace(FIRST.toString(), anotherSection.toString()));
        error(section(f, anotherSection), 404, "RESOURCE_NOT_FOUND");
        section(other, anotherSection).andExpect(status().isOk());
        var privateResult = fixture("another-user", true);
        error(section(privateResult, FIRST), 404, "RESOURCE_NOT_FOUND");
        error(section(f, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void sectionDoesNotRestoreDeletedRecordsOrExposeDrafts() throws Exception {
        var f = fixture(OWNER, true);
        jdbc.update("delete from analysis_records where id=?", f.recordId());
        error(section(f, FIRST), 404, "RESOURCE_NOT_FOUND");
        var draft = fixture(OWNER, false);
        error(section(draft, FIRST), 409, "ANALYSIS_NOT_READY");
    }

    @Test void malformedSectionIdAndCorruptSectionDetailsReturnTypedErrors() throws Exception {
        var f = fixture(OWNER, true);
        error(mvc.perform(get(BASE + f.analysisId() + "/sections/not-a-uuid")), 400, "INVALID_REQUEST");
        error(mvc.perform(get(BASE + "not-a-uuid/sections/" + FIRST)), 400, "INVALID_REQUEST");
        String valid = jdbc.queryForObject("select result_json from analysis_results where id=?", String.class, f.analysisId());
        corrupt(f, valid.replace("\"applicationTips\":[{\"text\":\"실무 팁\",\"origin\":\"AI_SUGGESTION\"}]", "\"applicationTips\":null"));
        error(section(f, FIRST), 500, "INTERNAL_ERROR");
    }

    private ResultActions section(Fixture f, UUID id) throws Exception {
        return mvc.perform(get(BASE + f.analysisId() + "/sections/" + id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "41"})
    void malformedStoredKeywordIdsAreNotExposedByEitherGet(String keywordId) throws Exception {
        var f = fixture(OWNER, true);
        String valid = jdbc.queryForObject("select result_json from analysis_results where id=?", String.class, f.analysisId());
        String invalid = valid.replace("\"keywordId\":\"33333333-3333-4333-8333-333333333333\"", "\"keywordId\":\"" + keywordId + "\"");
        assertThat(invalid).isNotEqualTo(valid);
        corrupt(f, invalid);
        error(result(f), 500, "INTERNAL_ERROR");
        error(section(f, FIRST), 500, "INTERNAL_ERROR");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingOrNullStoredStartTimeDoesNotBecomeZero(boolean missing) throws Exception {
        var f = fixture(OWNER, true);
        String valid = jdbc.queryForObject("select result_json from analysis_results where id=?", String.class, f.analysisId());
        String invalid = missing ? valid.replace("\"startMs\":1500,", "")
                : valid.replace("\"startMs\":1500", "\"startMs\":null");
        assertThat(invalid).isNotEqualTo(valid);
        corrupt(f, invalid);
        error(result(f), 500, "INTERNAL_ERROR");
        error(section(f, FIRST), 500, "INTERNAL_ERROR");
    }
    private JsonNode data(ResultActions action) throws Exception {
        return mapper.readTree(action.andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test void opensOwnedRecordWithItsFoldersAndRestoresSavedReadingPosition() throws Exception {
        var f = fixture(OWNER, true);
        UUID folderId = linkFolder(f.recordId());
        addSection(f.analysisId(), FIRST);
        String path = "/api/v1/records/" + f.recordId();

        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("data.record.recordId").value(f.recordId().toString()))
                .andExpect(jsonPath("data.analysis.analysisId").value(f.analysisId().toString()))
                .andExpect(jsonPath("data.folders[0].folderId").value(folderId.toString()))
                .andExpect(jsonPath("data.readingPosition").hasJsonPath())
                .andExpect(jsonPath("data.readingPosition").isEmpty());

        var saved = data(savePosition(path, """
                {"tab":"DETAIL","sectionId":"%s"}
                """.formatted(FIRST)).andExpect(status().isOk())).path("readingPosition");
        assertThat(saved.path("sectionId").asString()).isEqualTo(FIRST.toString());
        assertThat(saved.path("positionMs").isNull()).isTrue();
        var detail = data(mvc.perform(get(path)).andExpect(status().isOk()));
        assertThat(detail.path("readingPosition")).isEqualTo(saved);
        assertThat(detail.path("record").path("analyzedAt").asString()).isEqualTo(NOW.plusSeconds(10).toString());

        savePosition(path, """
                {"tab":"SUMMARY","sectionId":null,"positionMs":null}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("data.readingPosition.tab").value("SUMMARY"))
                .andExpect(jsonPath("data.readingPosition.sectionId").hasJsonPath())
                .andExpect(jsonPath("data.readingPosition.sectionId").isEmpty());
    }

    @Test void rejectsUnownedRecordsAndInvalidReadingPositions() throws Exception {
        var other = fixture("another-user", true);
        var mine = fixture(OWNER, true);
        addSection(other.analysisId(), FIRST);
        String otherPath = "/api/v1/records/" + other.recordId();
        String myPath = "/api/v1/records/" + mine.recordId();
        String valid = """
                {"tab":"SUMMARY","sectionId":null}
                """;
        error(mvc.perform(get(otherPath)), 404, "RESOURCE_NOT_FOUND");
        error(mvc.perform(delete(otherPath)), 404, "RESOURCE_NOT_FOUND");
        error(savePosition(otherPath, valid), 404, "RESOURCE_NOT_FOUND");
        error(savePosition(myPath, """
                {"tab":"SUMMARY"}
                """), 400, "INVALID_REQUEST");
        error(savePosition(myPath, """
                {"tab":"UNKNOWN","sectionId":null}
                """), 400, "INVALID_REQUEST");
        error(savePosition(myPath, """
                {"tab":"SUMMARY","sectionId":null,"positionMs":10}
                """), 400, "INVALID_REQUEST");
        error(savePosition(myPath, """
                {"tab":"DETAIL","sectionId":"%s"}
                """.formatted(FIRST)), 422, "VALIDATION_FAILED");
        mvc.perform(get(myPath)).andExpect(status().isOk())
                .andExpect(jsonPath("data.readingPosition").isEmpty());
    }

    @Test void deletesPersonalResultAndFolderLinksWhileRetainingSharedVideoAndCaption() throws Exception {
        var f = fixture(OWNER, true);
        var other = fixture("another-user", true);
        UUID folderId = linkFolder(f.recordId());
        addSection(f.analysisId(), FIRST);
        jdbc.update("""
                insert into analysis_chunks
                (id, owner_id, analysis_id, section_ids, source_segment_ids, text, start_ms, end_ms, source_type,
                 embedding, embedding_model, embedding_dimensions)
                values (?, ?, ?, array[?]::uuid[], array['segment-1'], 'test chunk', 0, 1000, 'CAPTION', '[1,0]'::vector, 'test', 2)
                """, UUID.randomUUID(), OWNER, f.analysisId(), FIRST);
        String path = "/api/v1/records/" + f.recordId();

        mvc.perform(delete(path)).andExpect(status().isOk()).andExpect(jsonPath("data").doesNotExist());
        error(mvc.perform(get(path)), 404, "RESOURCE_NOT_FOUND");
        error(result(f), 404, "RESOURCE_NOT_FOUND");
        error(section(f, FIRST), 404, "RESOURCE_NOT_FOUND");
        error(savePosition(path, """
                {"tab":"SUMMARY","sectionId":null}
                """), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.queryForObject("select count(*) from folder_records where record_id = ?", Integer.class, f.recordId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_chunks where analysis_id = ?", Integer.class, f.analysisId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_sections where analysis_id = ?", Integer.class, f.analysisId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from analysis_results where id = ?", Integer.class, f.analysisId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from folders where id = ?", Integer.class, folderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from analysis_records where id = ?", Integer.class, other.recordId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from videos where video_id = ?", Integer.class, VIDEO)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from caption_uploads", Integer.class)).isEqualTo(2);
        verifyNoInteractions(gcs);
    }

    private ResultActions savePosition(String path, String body) throws Exception {
        return mvc.perform(put(path + "/reading-position").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test void createsListsRenamesAndDeletesFoldersWithIdempotentCreationAndStableCursor() throws Exception {
        UUID key = UUID.randomUUID();
        String first = createFolder("첫 폴더", key);
        assertThat(createFolder("첫 폴더", key)).isEqualTo(first);
        error(requestFolder("다른 이름", key), 409, "IDEMPOTENCY_CONFLICT");
        error(requestFolder("   ", UUID.randomUUID()), 400, "INVALID_REQUEST");
        String second = createFolder("두 번째 폴더", UUID.randomUUID());
        jdbc.update("update folders set created_at = ?", java.sql.Timestamp.from(NOW));
        String latest = List.of(first, second).stream().max(String::compareTo).orElseThrow();
        String previous = latest.equals(first) ? second : first;

        var page = data(mvc.perform(get(FOLDERS).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].folderId").value(latest))
                .andExpect(jsonPath("data.items[0].recordCount").value(0))
                .andExpect(jsonPath("data.hasNext").value(true)));
        mvc.perform(get(FOLDERS).param("cursor", page.path("nextCursor").asString()).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].folderId").value(previous))
                .andExpect(jsonPath("data.hasNext").value(false));
        error(mvc.perform(get(FOLDERS).param("cursor", "bad!")), 400, "INVALID_CURSOR");

        String path = FOLDERS + "/" + first;
        mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"이름 변경"}
                """)).andExpect(status().isOk()).andExpect(jsonPath("data.name").value("이름 변경"));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("data.name").value("이름 변경"));
        mvc.perform(delete(path)).andExpect(status().isOk());
        error(mvc.perform(get(path)), 404, "RESOURCE_NOT_FOUND");
        error(requestFolder("첫 폴더", key), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void searchesLinkedRecordsAndCountsKeywordsWithoutChangingGlobalLatestOrDeletingOriginals() throws Exception {
        String secondVideo = "9bZkp7q19f0";
        tx.executeWithoutResult(status -> em.persist(new Video(secondVideo)));
        var older = fixture(OWNER, true);
        var newer = fixture(OWNER, true);
        jdbc.update("update analysis_records set analyzed_at = ? where id = ?",
                java.sql.Timestamp.from(NOW.plusSeconds(50)), newer.recordId());
        var second = fixture(OWNER, true, secondVideo);
        var foreign = fixture("another-user", true);
        String folder = createFolder("분석", UUID.randomUUID());
        String path = FOLDERS + "/" + folder;
        String otherFolder = createFolder("두 번째", UUID.randomUUID());

        error(addFolderRecords(path, older.recordId(), foreign.recordId()), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("data.recordCount").value(0));
        addFolderRecords(path, older.recordId(), second.recordId(), older.recordId()).andExpect(status().isOk())
                .andExpect(jsonPath("data.folderId").value(folder))
                .andExpect(jsonPath("data.recordCount").value(2))
                .andExpect(jsonPath("data.addedRecordIds.length()").value(2))
                .andExpect(jsonPath("data.addedRecordIds[0]").value(older.recordId().toString()))
                .andExpect(jsonPath("data.alreadyIncludedRecordIds").isEmpty());
        addFolderRecords(path, older.recordId(), second.recordId()).andExpect(status().isOk())
                .andExpect(jsonPath("data.folderId").value(folder))
                .andExpect(jsonPath("data.recordCount").value(2))
                .andExpect(jsonPath("data.addedRecordIds").isEmpty())
                .andExpect(jsonPath("data.alreadyIncludedRecordIds.length()").value(2));
        addFolderRecords(FOLDERS + "/" + otherFolder, older.recordId()).andExpect(status().isOk());

        mvc.perform(get(RECORDS).param("folderId", folder).param("q", "RAG")
                        .param("keywordIds", "33333333-3333-4333-8333-333333333333"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.matchedCount").value(2))
                .andExpect(jsonPath("data.filters.folderId").value(folder))
                .andExpect(jsonPath("data.items[*].recordId").value(org.hamcrest.Matchers.hasItem(older.recordId().toString())));
        mvc.perform(get(RECORD_KEYWORDS).param("folderId", folder).param("q", "RAG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.matchedCount").value(2))
                .andExpect(jsonPath("data.keywords[0].recordCount").value(2));

        var firstPage = data(mvc.perform(get(RECORDS).param("targetFolderId", folder).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.matchedCount").value(2))
                .andExpect(jsonPath("data.selectionContext.targetFolderId").value(folder))
                .andExpect(jsonPath("data.items[0].recordId").value(newer.recordId().toString()))
                .andExpect(jsonPath("data.items[0].folderMembership.included").value(false)));
        String cursor = firstPage.path("nextCursor").asString();
        mvc.perform(get(RECORDS).param("targetFolderId", folder).param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].folderMembership.included").value(true));
        error(mvc.perform(get(RECORDS).param("targetFolderId", otherFolder).param("cursor", cursor)), 400, "INVALID_CURSOR");

        addFolderRecords(path, newer.recordId()).andExpect(status().isOk())
                .andExpect(jsonPath("data.recordCount").value(3));
        mvc.perform(get(RECORDS).param("folderId", folder))
                .andExpect(status().isOk()).andExpect(jsonPath("data.matchedCount").value(3));
        mvc.perform(delete(path + "/records/" + older.recordId())).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("data.recordCount").value(2));
        mvc.perform(delete(path)).andExpect(status().isOk());
        error(mvc.perform(get(RECORDS).param("folderId", folder)), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(get(RECORDS + "/" + older.recordId())).andExpect(status().isOk())
                .andExpect(jsonPath("data.folders[0].folderId").value(otherFolder));
        result(newer).andExpect(status().isOk());
        result(second).andExpect(status().isOk());
    }

    @Test void hidesForeignFoldersFromListsWritesAndSearch() throws Exception {
        var mine = fixture(OWNER, true);
        UUID foreign = UUID.randomUUID();
        jdbc.update("insert into folders(id, owner_id, name, created_at) values (?, ?, ?, now())",
                foreign, "another-user", "비공개");
        String path = FOLDERS + "/" + foreign;
        mvc.perform(get(FOLDERS)).andExpect(status().isOk()).andExpect(jsonPath("data.items").isEmpty());
        error(mvc.perform(get(path)), 404, "RESOURCE_NOT_FOUND");
        error(mvc.perform(patch(path).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"변경"}
                """)), 404, "RESOURCE_NOT_FOUND");
        error(mvc.perform(delete(path)), 404, "RESOURCE_NOT_FOUND");
        error(addFolderRecords(path, mine.recordId()), 404, "RESOURCE_NOT_FOUND");
        error(mvc.perform(delete(path + "/records/" + mine.recordId())), 404, "RESOURCE_NOT_FOUND");
        error(mvc.perform(get(RECORDS).param("targetFolderId", foreign.toString())), 404, "RESOURCE_NOT_FOUND");
        error(mvc.perform(get(RECORD_KEYWORDS).param("folderId", foreign.toString())), 404, "RESOURCE_NOT_FOUND");
    }

    @Test void groupsPlaylistBatchesByOwnerAndFiltersTheirCompletedRecords() throws Exception {
        String otherVideo = "9bZkp7q19f0";
        tx.executeWithoutResult(status -> em.persist(new Video(otherVideo)));
        var first = fixture(OWNER, true);
        var second = fixture(OWNER, true, otherVideo);
        var foreign = fixture("another-user", true);
        createPlaylistBatch("PL_test_a", VIDEO, otherVideo);
        createPlaylistBatch("PL_test_a", VIDEO);
        createPlaylistBatch("PL_test_b", otherVideo);
        jdbc.update("insert into youtube_playlist_metadata (playlist_id, title) values (?, ?)", "PL_test_a", "공개 제목");
        tx.executeWithoutResult(status -> {
            var batch = new AnalysisBatch("another-user", "PL_foreign", NOW);
            em.persist(batch);
            em.persist(AnalysisBatchItem.openResult(batch.getId(), VIDEO, foreign.analysisId(), foreign.recordId(), NOW));
        });

        var page = data(mvc.perform(get(PLAYLIST_GROUPS).param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.hasNext").value(true)));
        mvc.perform(get(PLAYLIST_GROUPS).param("cursor", page.path("nextCursor").asString()))
                .andExpect(status().isOk()).andExpect(jsonPath("data.items.length()").value(1))
                .andExpect(jsonPath("data.hasNext").value(false));
        error(mvc.perform(get(PLAYLIST_GROUPS).param("cursor", "invalid!")), 400, "INVALID_CURSOR");

        var groups = data(mvc.perform(get(PLAYLIST_GROUPS)).andExpect(status().isOk())).path("items");
        var group = groups.get(0).path("playlistId").asString().equals("PL_test_a") ? groups.get(0) : groups.get(1);
        String groupId = group.path("groupId").asString();
        mvc.perform(get(PLAYLIST_GROUPS + "/" + groupId)).andExpect(status().isOk())
                .andExpect(jsonPath("data.playlistId").value("PL_test_a"))
                .andExpect(jsonPath("data.title").value("공개 제목"))
                .andExpect(jsonPath("data.originalUrl").value("https://www.youtube.com/playlist?list=PL_test_a"))
                .andExpect(jsonPath("data.recordCount").value(2));
        UUID foreignId = jdbc.queryForObject("select md5(jsonb_build_array(?::text, ?::text)::text)::uuid",
                UUID.class, "another-user", "PL_foreign");
        error(mvc.perform(get(PLAYLIST_GROUPS + "/" + foreignId)), 404, "RESOURCE_NOT_FOUND");
        error(mvc.perform(get(RECORDS).param("playlistGroupId", foreignId.toString())), 404, "RESOURCE_NOT_FOUND");

        String folderId = createFolder("재생목록 일부", UUID.randomUUID());
        addFolderRecords(FOLDERS + "/" + folderId, first.recordId()).andExpect(status().isOk());
        mvc.perform(get(RECORDS).param("playlistGroupId", groupId).param("folderId", folderId).param("q", "RAG"))
                .andExpect(status().isOk()).andExpect(jsonPath("data.matchedCount").value(1))
                .andExpect(jsonPath("data.items[0].recordId").value(first.recordId().toString()));
        mvc.perform(get(RECORD_KEYWORDS).param("playlistGroupId", groupId)).andExpect(status().isOk())
                .andExpect(jsonPath("data.matchedCount").value(2))
                .andExpect(jsonPath("data.keywords[0].recordCount").value(2));
        mvc.perform(delete(RECORDS + "/" + first.recordId())).andExpect(status().isOk());
        mvc.perform(get(PLAYLIST_GROUPS + "/" + groupId)).andExpect(status().isOk())
                .andExpect(jsonPath("data.recordCount").value(1));
        mvc.perform(delete(RECORDS + "/" + second.recordId())).andExpect(status().isOk());
        error(mvc.perform(get(PLAYLIST_GROUPS + "/" + groupId)), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(get(PLAYLIST_GROUPS)).andExpect(status().isOk()).andExpect(jsonPath("data.items").isEmpty());
    }

    @Test void followsPlaylistJobRetriesAndOmitsUnfinishedRecords() throws Exception {
        var previous = fixture(OWNER, false);
        jdbc.update("""
                update analysis_jobs set status='FAILED', next_attempt_at=null, error_code='TEST', error_message='Test', retryable=true
                where id=?
                """, previous.jobId());
        jdbc.update("update analysis_batches set playlist_id = 'PL_retry' where id = (select batch_id from analysis_jobs where id = ?)",
                previous.jobId());
        jdbc.update("update analysis_batch_items set job_id = ?, status = 'QUEUED' where id = (select batch_item_id from analysis_jobs where id = ?)",
                previous.jobId(), previous.jobId());
        mvc.perform(get(PLAYLIST_GROUPS)).andExpect(status().isOk()).andExpect(jsonPath("data.items").isEmpty());
        var retried = fixture(OWNER, true);
        jdbc.update("update analysis_jobs set previous_job_id = ? where id = ?", previous.jobId(), retried.jobId());
        var groups = data(mvc.perform(get(PLAYLIST_GROUPS)).andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].playlistId").value("PL_retry"))
                .andExpect(jsonPath("data.items[0].recordCount").value(1)));
        mvc.perform(get(RECORDS).param("playlistGroupId", groups.path("items").get(0).path("groupId").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("data.items[0].recordId").value(retried.recordId().toString()));
    }

    @Test void homeKeepsRecentAnalysesSeparateFromReadingPositionAndDoesNotUpdateIt() throws Exception {
        mvc.perform(get(HOME)).andExpect(status().isOk()).andExpect(jsonPath("data.recentAnalyses").isEmpty())
                .andExpect(jsonPath("data.continueReading").value(org.hamcrest.Matchers.nullValue()));
        var opened = fixture(OWNER, true);
        var newest = fixture(OWNER, true);
        var other = fixture("another-user", true);
        jdbc.update("update analysis_records set analyzed_at = ? where id = ?",
                java.sql.Timestamp.from(NOW.plusSeconds(50)), newest.recordId());
        jdbc.update("update analysis_records set reading_tab='SUMMARY', last_viewed_at = now() + interval '1 day' where id = ?",
                other.recordId());
        addSection(opened.analysisId(), FIRST);
        var saved = data(savePosition(RECORDS + "/" + opened.recordId(), """
                {"tab":"DETAIL","sectionId":"%s"}
                """.formatted(FIRST)).andExpect(status().isOk())).path("readingPosition");
        var home = data(mvc.perform(get(HOME)).andExpect(status().isOk())
                .andExpect(jsonPath("data.recentAnalyses.length()").value(2))
                .andExpect(jsonPath("data.recentAnalyses[0].recordId").value(newest.recordId().toString()))
                .andExpect(jsonPath("data.recentAnalyses[0].oneLine").value("영상 전체 한 줄 요약"))
                .andExpect(jsonPath("data.recentAnalyses[0].keywords[0].name").value("RAG"))
                .andExpect(jsonPath("data.recentAnalyses[0].lastViewedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("data.continueReading.recordId").value(opened.recordId().toString()))
                .andExpect(jsonPath("data.continueReading.analysisId").value(opened.analysisId().toString()))
                .andExpect(jsonPath("data.continueReading.videoTitle").value("RAG 원본 영상"))
                .andExpect(jsonPath("data.continueReading.tab").value("DETAIL"))
                .andExpect(jsonPath("data.continueReading.sectionId").value(FIRST.toString()))
                .andExpect(jsonPath("data.continueReading.sectionTitle").value("검색과 답변"))
                .andExpect(jsonPath("data.continueReading.startMs").value(1500))
                .andExpect(jsonPath("data.continueReading.endMs").value(60000))
                .andExpect(jsonPath("data.continueReading.positionMs").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("data.continueReading.sectionSummary").value("첫 구간 요약"))
                .andExpect(jsonPath("data.continueReading.practicalTipCount").value(1)));
        assertThat(home.path("continueReading").path("lastViewedAt")).isEqualTo(saved.path("lastViewedAt"));
        assertThat(home.path("recentAnalyses").get(1).path("lastViewedAt")).isEqualTo(saved.path("lastViewedAt"));
        assertThat(data(mvc.perform(get(HOME)).andExpect(status().isOk()))).isEqualTo(home);
        mvc.perform(delete(RECORDS + "/" + opened.recordId())).andExpect(status().isOk());
        mvc.perform(get(HOME)).andExpect(status().isOk()).andExpect(jsonPath("data.recentAnalyses.length()").value(1))
                .andExpect(jsonPath("data.continueReading").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void homeRequiresAnExistingSectionForContinueReading() throws Exception {
        var record = fixture(OWNER, true);
        savePosition(RECORDS + "/" + record.recordId(), "{\"tab\":\"SUMMARY\",\"sectionId\":null}")
                .andExpect(status().isOk());
        mvc.perform(get(HOME)).andExpect(status().isOk())
                .andExpect(jsonPath("data.continueReading").value(org.hamcrest.Matchers.nullValue()));
        addSection(record.analysisId(), FIRST);
        savePosition(RECORDS + "/" + record.recordId(), """
                {"tab":"DETAIL","sectionId":"%s"}
                """.formatted(FIRST)).andExpect(status().isOk());
        mvc.perform(get(HOME)).andExpect(status().isOk())
                .andExpect(jsonPath("data.continueReading.sectionId").value(FIRST.toString()));
        jdbc.update("delete from analysis_sections where id=? and analysis_id=?", FIRST, record.analysisId());
        mvc.perform(get(HOME)).andExpect(status().isOk())
                .andExpect(jsonPath("data.continueReading").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void homeExcludesForeignAndDraftDocumentsBeforeParsingAndFailsClosedOnCorruption() throws Exception {
        var mine = fixture(OWNER, true);
        corrupt(fixture("another-user", true), "{private-broken-json");
        corrupt(fixture(OWNER, false), "{draft-broken-json");
        mvc.perform(get(HOME)).andExpect(status().isOk())
                .andExpect(jsonPath("data.recentAnalyses.length()").value(1));
        corrupt(mine, "{broken-json");
        error(mvc.perform(get(HOME)), 500, "INTERNAL_ERROR");
        verifyNoInteractions(gcs);
    }

    private void createPlaylistBatch(String playlistId, String... videoIds) throws Exception {
        mvc.perform(post("/api/v1/analysis-batches").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("playlistId", playlistId, "videoIds", List.of(videoIds)))))
                .andExpect(status().isCreated());
    }

    private String createFolder(String name, UUID key) throws Exception {
        var created = requestFolder(name, key).andExpect(status().isCreated()).andExpect(header().exists("Location"));
        String id = data(created).path("folderId").asString();
        created.andExpect(header().string("Location", FOLDERS + "/" + id));
        return id;
    }

    private ResultActions requestFolder(String name, UUID key) throws Exception {
        return mvc.perform(post(FOLDERS).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", name))));
    }

    private ResultActions addFolderRecords(String path, UUID... ids) throws Exception {
        return mvc.perform(post(path + "/records").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("recordIds", List.of(ids)))));
    }

    private UUID linkFolder(UUID recordId) {
        UUID folderId = UUID.randomUUID();
        jdbc.update("insert into folders(id, owner_id, name, created_at) values (?, ?, ?, ?)",
                folderId, OWNER, "보관 폴더", java.sql.Timestamp.from(NOW));
        jdbc.update("insert into folder_records(folder_id, record_id, owner_id) values (?, ?, ?)", folderId, recordId, OWNER);
        return folderId;
    }

    private void addSection(UUID analysisId, UUID sectionId) {
        jdbc.update("""
                insert into analysis_sections(id, analysis_id, title, start_ms, end_ms, detail_json)
                values (?, ?, '구간', 0, 1000, '{}')
                """, sectionId, analysisId);
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
        return fixture(owner, ready, VIDEO);
    }

    private Fixture fixture(String owner, boolean ready, String videoId) {
        return tx.execute(status -> {
            var batch = new AnalysisBatch(owner, null, NOW); em.persist(batch);
            var item = AnalysisBatchItem.collectCaptions(batch.getId(), videoId, NOW.plusSeconds(1800), NOW); em.persist(item);
            var upload = CaptionUpload.issued(owner, item.getId(), videoId, "test-bucket", UUID.randomUUID() + ".json",
                    "application/json", 100, NOW.plusSeconds(600)); em.persist(upload); upload.consume(7);
            var job = AnalysisJob.queued(owner, batch.getId(), item.getId(), videoId, upload.getId(), 7, null, NOW); em.persist(job);
            // The persisted JSON is the Worker output boundary; GET must not regenerate its IDs or contents.
            var result = AnalysisResult.draft(UUID.randomUUID(), owner, videoId, job.getId(), mapper.writeValueAsString(document()), NOW);
            em.persist(result);
            UUID recordId = null;
            if (ready) {
                result.markReady(NOW.plusSeconds(10));
                var record = AnalysisRecord.fromCompleted(result); em.persist(record); recordId = record.getId();
                var token = job.claim(NOW, Duration.ofMinutes(1));
                job.changeStage(token, AnalysisStage.FINISHING, NOW);
                job.complete(token, result.getId(), recordId, NOW.plusSeconds(10));
            }
            em.flush();
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
