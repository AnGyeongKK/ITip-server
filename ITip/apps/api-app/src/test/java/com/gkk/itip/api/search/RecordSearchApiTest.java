package com.gkk.itip.api.search;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse.VideoInfo;
import com.gkk.itip.api.search.controller.RecordSearchController;
import com.gkk.itip.api.search.repository.RecordSearchRepository;
import com.gkk.itip.api.search.repository.RecordSearchRepository.StoredRecord;
import com.gkk.itip.api.search.service.RecordSearchService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.web.ApiExceptionHandler;
import com.gkk.itip.domain.analysis.result.ResultDocument;
import com.gkk.itip.domain.analysis.result.ResultDocument.Keyword;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecordSearchApiTest {
    private static final String OWNER = "demo-user";
    private static final String RECORDS = "/api/v1/records";
    private static final String KEYWORDS = "/api/v1/record-keywords";
    private static final String FIRST = "80000000-0000-4000-8000-000000000001";
    private static final String SECOND = "70000000-0000-4000-8000-000000000002";
    private static final String SECTION = "30000000-0000-4000-8000-000000000003";
    private static final Instant NOW = Instant.parse("2026-11-10T01:00:00Z");
    private static final Keyword RAG = new Keyword("11111111-1111-4111-8111-111111111111", "RAG");
    private static final Keyword LLM = new Keyword("22222222-2222-4222-8222-222222222222", "LLM");
    private static final Keyword SPRING = new Keyword("33333333-3333-4333-8333-333333333333", "Spring");
    private static final Keyword PGVECTOR = new Keyword("44444444-4444-4444-8444-444444444444", "pgvector");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var repository = mock(RecordSearchRepository.class);
        List<StoredRecord> records = List.of(
                record(FIRST, "video-1", "RAG Guide", List.of(RAG, LLM), "vector   database", List.of(RAG, PGVECTOR)),
                record(SECOND, "video-2", "Spring Guide", List.of(RAG, SPRING), "query planner", List.of()),
                record("60000000-0000-4000-8000-000000000003", "video-3", "Other Guide", List.of(LLM), "decoder", List.of()));
        doAnswer(invocation -> {
            Consumer<StoredRecord> consumer = invocation.getArgument(1);
            records.forEach(consumer);
            return null;
        }).when(repository).scanCompleted(eq(OWNER), any());
        mvc = MockMvcBuilders.standaloneSetup(
                        new RecordSearchController(new RecordSearchService(repository, mapper), new DemoUserProvider(OWNER)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void paginatesWithUuidTieBreakerAndRetainsWholeMatchCount() throws Exception {
        var response = mvc.perform(get(RECORDS).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].recordId").value(FIRST))
                .andExpect(jsonPath("$.data.items[0].matches").isEmpty())
                .andExpect(jsonPath("$.data.items[0].folderMembership").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].video.channelImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.matchedCount").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        String cursor = mapper.readTree(response).path("data").path("nextCursor").asString();
        mvc.perform(get(RECORDS).param("cursor", cursor).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].recordId").value(SECOND))
                .andExpect(jsonPath("$.data.matchedCount").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
        mvc.perform(get(RECORDS).param("cursor", cursor).param("q", "RAG"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void findsDetailTechnologyAndToolsWithTheirSectionReferences() throws Exception {
        mvc.perform(get(RECORDS).param("q", "  VECTOR   database "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedCount").value(1))
                .andExpect(jsonPath("$.data.items[0].matches[0].field").value("TECHNOLOGY"))
                .andExpect(jsonPath("$.data.items[0].matches[0].sectionId").value(SECTION));
        mvc.perform(get(RECORDS).param("q", "Details on Spring Guide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].matches[0].field").value("DETAIL"));
        mvc.perform(get(RECORDS).param("q", "psql"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].matches[0].field").value("TOOL"));
    }

    @Test
    void preservesLiteralInternalSpacesLikeFilteredChat() throws Exception {
        mvc.perform(get(RECORDS).param("q", "  vector   database  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filters.q").value("vector   database"))
                .andExpect(jsonPath("$.data.matchedCount").value(1));
        mvc.perform(get(RECORDS).param("q", "vector database"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedCount").value(0));
    }

    @Test
    void exposesOnlySummaryFilterKeywordsButStillSearchesSectionKeywordText() throws Exception {
        mvc.perform(get(KEYWORDS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keywords[*].keywordId", not(hasItem(PGVECTOR.keywordId()))));
        mvc.perform(get(RECORDS).param("keywordIds", PGVECTOR.keywordId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedCount").value(0));
        mvc.perform(get(RECORDS).param("q", "pgvector").param("keywordIds", RAG.keywordId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedCount").value(1))
                .andExpect(jsonPath("$.data.items[0].keywords.length()").value(2));
    }

    @Test
    void usesSameTextAndKeywordAndFiltersForRecordsAndFacets() throws Exception {
        mvc.perform(get(KEYWORDS).param("q", "Guide").param("keywordIds", RAG.keywordId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedCount").value(2))
                .andExpect(jsonPath("$.data.keywords[0].keywordId").value(RAG.keywordId()))
                .andExpect(jsonPath("$.data.keywords[0].recordCount").value(2))
                .andExpect(jsonPath("$.data.keywords[0].selected").value(true))
                .andExpect(jsonPath("$.data.keywords[1].keywordId").value(LLM.keywordId()))
                .andExpect(jsonPath("$.data.keywords[1].recordCount").value(1));
        mvc.perform(get(RECORDS).param("q", "Guide").param("keywordIds", LLM.keywordId(), RAG.keywordId(), RAG.keywordId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedCount").value(1))
                .andExpect(jsonPath("$.data.items[0].recordId").value(FIRST))
                .andExpect(jsonPath("$.data.items[0].keywords.length()").value(2));
    }

    @Test
    void keepsSelectedKeywordWhenNoRecordsMatch() throws Exception {
        mvc.perform(get(KEYWORDS).param("q", "No match").param("keywordIds", RAG.keywordId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedCount").value(0))
                .andExpect(jsonPath("$.data.keywords[0].name").value("RAG"))
                .andExpect(jsonPath("$.data.keywords[0].recordCount").value(0))
                .andExpect(jsonPath("$.data.keywords[0].selected").value(true));
        mvc.perform(get(RECORDS).param("q", "No match"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.matchedCount").value(0));
    }

    @Test
    void rejectsInvalidPaginationAndUnsupportedScopes() throws Exception {
        mvc.perform(get(RECORDS).param("cursor", "!!"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(get(RECORDS).param("limit", "51"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(RECORDS).param("targetFolderId", "21")).andExpect(status().isNotFound());
        mvc.perform(get(KEYWORDS).param("playlistGroupId", "31")).andExpect(status().isNotFound());
    }

    private StoredRecord record(String id, String videoId, String title, List<Keyword> keywords, String technology,
                                List<Keyword> sectionKeywords) {
        var detail = new ResultDocument.Detail("takeaway", "Details on " + title, List.of(),
                List.of(new ResultDocument.RecommendedTool("psql", "reason", "SOURCE")), List.of(technology), List.of());
        var section = new ResultDocument.Section(SECTION, "Section", 0, 2000, "Summary", List.of(), sectionKeywords, detail);
        var summary = new ResultDocument.Summary("A short summary", List.of(), List.of(), List.of(), keywords, List.of(SECTION));
        var document = new ResultDocument("1.0", summary, List.of(section), new ResultDocument.Mindmap(title, List.of(SECTION)));
        return new StoredRecord(UUID.fromString(id), UUID.randomUUID(), NOW,
                new VideoInfo(videoId, title, null, null, null, null, null), mapper.writeValueAsString(document));
    }
}
