package com.gkk.itip.worker.analysis.output;

import java.util.List;

/** 모델의 sectionKey·원문 참조를 받는 내부 계약. DB ID는 모델이 생성하지 않는다. */
public record AnalysisOutput(String schemaVersion, Summary summary, List<Section> sections, Mindmap mindmap) {
    public record Summary(String oneLine, List<String> keyPoints, List<TextPoint> practicalPoints,
                          List<MentionedTool> mentionedTools, List<String> keywords, List<String> highlightSectionKeys) {}
    public record TextPoint(String text, String origin, List<String> sourceSegmentIds) {}
    public record MentionedTool(String name, List<String> sourceSegmentIds) {}
    public record RecommendedTool(String name, String reason, String origin, List<String> sourceSegmentIds) {}
    public record Section(String sectionKey, String title, String startSegmentId, String endSegmentId,
                          List<String> sourceSegmentIds, String summary, List<String> subtopics,
                          List<String> keywords, Detail detail) {}
    public record Detail(String takeaway, String explanation, List<TextPoint> applicationTips,
                         List<RecommendedTool> recommendedTools, List<String> usageKeywords, List<TextPoint> examples) {}
    public record Mindmap(String rootTitle, List<String> sectionKeys) {}
}
