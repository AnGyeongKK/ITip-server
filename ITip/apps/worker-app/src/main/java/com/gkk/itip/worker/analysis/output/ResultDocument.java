package com.gkk.itip.worker.analysis.output;

import java.util.List;

/** 검증 후 저장하는 화면 데이터. 공개 API 응답과는 별도로 변환한다. */
public record ResultDocument(String schemaVersion, Summary summary, List<Section> sections, Mindmap mindmap) {
    public record Summary(String oneLine, List<String> keyPoints, List<TextPoint> practicalPoints,
                          List<MentionedTool> mentionedTools, List<Keyword> keywords, List<String> highlightSectionIds) {}
    public record TextPoint(String text, String origin) {}
    public record MentionedTool(String name) {}
    public record RecommendedTool(String name, String reason, String origin) {}
    public record Keyword(String keywordId, String name) {}
    public record Section(String sectionId, String title, long startMs, long endMs, String summary,
                          List<String> subtopics, List<Keyword> keywords, Detail detail) {}
    public record Detail(String takeaway, String explanation, List<TextPoint> applicationTips,
                         List<RecommendedTool> recommendedTools, List<String> usageKeywords, List<TextPoint> examples) {}
    public record Mindmap(String rootTitle, List<String> sectionIds) {}
}
