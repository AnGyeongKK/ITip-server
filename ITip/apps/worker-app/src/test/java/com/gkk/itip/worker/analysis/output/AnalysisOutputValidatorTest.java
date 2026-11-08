package com.gkk.itip.worker.analysis.output;

import com.gkk.itip.worker.analysis.caption.CaptionDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AnalysisOutputValidatorTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AnalysisOutputValidator validator = new AnalysisOutputValidator();
    private final CaptionDocument captions = new CaptionDocument("dQw4w9WgXcQ", "ko", List.of(
            new CaptionDocument.Segment("s000001", 1000, 4000, "검색 근거를 저장합니다."),
            new CaptionDocument.Segment("s000002", 3000, 5000, "검색 결과로 답변합니다.")));
    private static final String VALID = """
            {"schemaVersion":"1.0","summary":{
             "oneLine":"근거 기반 검색과 답변","keyPoints":["원문 검색"],
             "practicalPoints":[{"text":"근거를 비교하세요.","origin":"AI_SUGGESTION","sourceSegmentIds":["s000001"]}],
             "mentionedTools":[],"keywords":["RAG"],"highlightSectionKeys":["topic-a"]},
             "sections":[{"sectionKey":"topic-a","title":"근거 검색","startSegmentId":"s000001","endSegmentId":"s000002",
              "sourceSegmentIds":["s000001","s000002"],"summary":"원문을 근거로 답변합니다.","subtopics":["검색"],"keywords":["RAG"],
              "detail":{"takeaway":"검색한 근거를 확인합니다.","explanation":"원문에 근거한 검색 과정을 설명합니다.",
               "applicationTips":[],"recommendedTools":[],"usageKeywords":[],"examples":[]}}],
             "mindmap":{"rootTitle":"근거 검색","sectionKeys":["topic-a"]}}
            """;

    @Test void assignsServerSectionIdsAndResolvesTimesFromRealSegments() {
        var result = validate(VALID);
        var section = result.sections().getFirst();
        assertThatCode(() -> UUID.fromString(section.sectionId())).doesNotThrowAnyException();
        assertThat(section.sectionId()).isNotEqualTo("topic-a");
        assertThat(section.startMs()).isEqualTo(1000);
        assertThat(section.endMs()).isEqualTo(5000);
        assertThat(result.summary().highlightSectionIds()).containsExactly(section.sectionId());
        assertThat(result.mindmap().sectionIds()).containsExactly(section.sectionId());
        assertThat(result.summary().keywords().getFirst().keywordId())
                .isEqualTo(section.keywords().getFirst().keywordId());
        assertThat(section.detail().explanation()).contains("원문");
    }

    @Test void rejectsUnknownSourceSegmentsAndSectionReferences() {
        rejects(VALID.replace("s000002", "invented-source"));
        rejects(VALID.replace("\"highlightSectionKeys\":[\"topic-a\"]", "\"highlightSectionKeys\":[\"invented-section\"]"));
        rejects(VALID.replace("\"sectionKeys\":[\"topic-a\"]", "\"sectionKeys\":[\"invented-section\"]"));
    }

    @Test void rejectsUnsupportedSchemaAndMissingRequiredStructure() {
        rejects(VALID.replace("\"1.0\"", "\"2.0\""));
        rejects(VALID.replace("\"mentionedTools\":[]", "\"mentionedTools\":null"));
        rejects(VALID.replace("근거 기반 검색과 답변", " "));
        rejects(VALID.replace("\"takeaway\":\"검색한 근거를 확인합니다.\"", "\"takeaway\":null"));
    }

    @Test void rejectsReversedSourceRangeAndUnsupportedOrigins() {
        rejects(VALID.replace("\"startSegmentId\":\"s000001\",\"endSegmentId\":\"s000002\"",
                "\"startSegmentId\":\"s000002\",\"endSegmentId\":\"s000001\""));
        rejects(VALID.replace("AI_SUGGESTION", "UNVERIFIED"));
    }

    @Test void requiresSourceEvidenceAndAConnectedMindmap() {
        rejects(VALID.replace("\"sectionKeys\":[\"topic-a\"]", "\"sectionKeys\":[]"));
        rejects(VALID.replace("\"origin\":\"AI_SUGGESTION\",\"sourceSegmentIds\":[\"s000001\"]",
                "\"origin\":\"SOURCE\",\"sourceSegmentIds\":[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"applicationTips", "recommendedTools", "examples"})
    void detailEvidenceMustBelongToItsSection(String field) {
        String item = field.equals("recommendedTools")
                ? "{\"name\":\"도구\",\"reason\":\"다른 구간의 설명\",\"origin\":\"SOURCE\",\"sourceSegmentIds\":[\"s000002\"]}"
                : "{\"text\":\"다른 구간의 설명\",\"origin\":\"SOURCE\",\"sourceSegmentIds\":[\"s000002\"]}";
        rejects(firstSectionOnly().replace("\"" + field + "\":[]", "\"" + field + "\":[" + item + "]"));
    }

    @Test void wholeVideoSummaryCanStillReferenceAnotherSection() {
        String json = firstSectionOnly().replaceFirst("\\[\"s000001\"\\]", "[\"s000002\"]");
        assertThatCode(() -> validate(json)).doesNotThrowAnyException();
    }

    private String firstSectionOnly() {
        return VALID.replace("\"endSegmentId\":\"s000002\"", "\"endSegmentId\":\"s000001\"")
                .replace("\"sourceSegmentIds\":[\"s000001\",\"s000002\"]", "\"sourceSegmentIds\":[\"s000001\"]");
    }

    private ResultDocument validate(String json) {
        return validator.validate(mapper.readValue(json, AnalysisOutput.class), captions);
    }
    private void rejects(String json) {
        assertThatThrownBy(() -> validate(json)).isInstanceOf(InvalidAnalysisOutputException.class);
    }
}
