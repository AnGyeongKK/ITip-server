package com.gkk.itip.domain.analysis.result;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class ResultDocumentValidator {
    private ResultDocumentValidator() {}
    public static void validate(ResultDocument document) {
        require("1.0".equals(document.schemaVersion()));
        var summary = document.summary();
        text(summary.oneLine()); texts(summary.keyPoints()); points(summary.practicalPoints());
        summary.mentionedTools().forEach(tool -> text(tool.name())); keywords(summary.keywords());
        require(!document.sections().isEmpty());
        var ids = new HashSet<String>();
        for (var section : document.sections()) {
            require(UUID.fromString(section.sectionId()).toString().equals(section.sectionId()) && ids.add(section.sectionId()));
            text(section.title()); text(section.summary()); texts(section.subtopics()); keywords(section.keywords());
            require(section.startMs() >= 0 && section.endMs() > section.startMs());
            var detail = section.detail();
            text(detail.takeaway()); text(detail.explanation()); points(detail.applicationTips()); points(detail.examples());
            texts(detail.usageKeywords());
            detail.recommendedTools().forEach(tool -> { text(tool.name()); text(tool.reason()); origin(tool.origin()); });
        }
        text(document.mindmap().rootTitle());
        require(!document.mindmap().sectionIds().isEmpty());
        for (var references : List.of(summary.highlightSectionIds(), document.mindmap().sectionIds()))
            require(references.stream().distinct().count() == references.size() && ids.containsAll(references));
    }
    private static void texts(List<String> values) { values.forEach(ResultDocumentValidator::text); }
    private static void keywords(List<ResultDocument.Keyword> values) {
        values.forEach(keyword -> { require(UUID.fromString(keyword.keywordId()).toString().equals(keyword.keywordId())); text(keyword.name()); });
    }
    private static void points(List<ResultDocument.TextPoint> values) { values.forEach(point -> { text(point.text()); origin(point.origin()); }); }
    private static void text(String value) { require(value != null && !value.isBlank()); }
    private static void origin(String value) { require("SOURCE".equals(value) || "AI_SUGGESTION".equals(value)); }
    private static void require(boolean valid) { if (!valid) throw new IllegalStateException("invalid stored analysis result"); }
}
