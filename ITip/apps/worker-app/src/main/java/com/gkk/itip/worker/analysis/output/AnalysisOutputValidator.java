package com.gkk.itip.worker.analysis.output;

import com.gkk.itip.worker.analysis.caption.CaptionDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

@Component
public class AnalysisOutputValidator {
    public ResultDocument validate(AnalysisOutput output, CaptionDocument captions) {
        try {
            require("1.0".equals(output.schemaVersion()));
            require(!output.sections().isEmpty());
            require(!output.mindmap().sectionKeys().isEmpty());
            Map<String, Integer> sourceIndex = new HashMap<>();
            for (int i = 0; i < captions.segments().size(); i++) sourceIndex.put(captions.segments().get(i).segmentId(), i);
            Map<String, String> sectionIds = new LinkedHashMap<>();
            for (var section : output.sections()) {
                require(sectionIds.putIfAbsent(text(section.sectionKey()), UUID.randomUUID().toString()) == null);
            }
            var sections = output.sections().stream().map(section -> section(section, sectionIds, sourceIndex, captions)).toList();
            var summary = output.summary();
            var mappedSummary = new ResultDocument.Summary(text(summary.oneLine()), texts(summary.keyPoints()),
                    points(summary.practicalPoints(), sourceIndex), summary.mentionedTools().stream().map(tool -> {
                        references(tool.sourceSegmentIds(), sourceIndex, true);
                        return new ResultDocument.MentionedTool(text(tool.name()));
                    }).toList(), keywords(summary.keywords()), sectionReferences(summary.highlightSectionKeys(), sectionIds));
            return new ResultDocument("1.0", mappedSummary, sections,
                    new ResultDocument.Mindmap(text(output.mindmap().rootTitle()), sectionReferences(output.mindmap().sectionKeys(), sectionIds)));
        } catch (NullPointerException | IllegalArgumentException exception) {
            throw new InvalidAnalysisOutputException();
        }
    }

    private ResultDocument.Section section(AnalysisOutput.Section section, Map<String, String> ids,
                                           Map<String, Integer> sourceIndex, CaptionDocument captions) {
        references(section.sourceSegmentIds(), sourceIndex, true);
        Integer start = sourceIndex.get(section.startSegmentId()), end = sourceIndex.get(section.endSegmentId());
        require(start != null && end != null && start <= end);
        for (String reference : section.sourceSegmentIds()) require(sourceIndex.get(reference) >= start && sourceIndex.get(reference) <= end);
        var range = captions.segments().subList(start, end + 1);
        long startMs = range.getFirst().startMs();
        long endMs = range.stream().mapToLong(CaptionDocument.Segment::endMs).max().orElseThrow();
        require(text(section.title()).length() <= 500);
        var detail = section.detail();
        var mappedDetail = new ResultDocument.Detail(text(detail.takeaway()), text(detail.explanation()),
                points(detail.applicationTips(), sourceIndex), detail.recommendedTools().stream().map(tool -> {
                    origin(tool.origin()); references(tool.sourceSegmentIds(), sourceIndex, "SOURCE".equals(tool.origin()));
                    return new ResultDocument.RecommendedTool(text(tool.name()), text(tool.reason()), tool.origin());
                }).toList(), texts(detail.usageKeywords()), points(detail.examples(), sourceIndex));
        return new ResultDocument.Section(ids.get(section.sectionKey()), section.title(), startMs, endMs,
                text(section.summary()), texts(section.subtopics()), keywords(section.keywords()), mappedDetail);
    }

    private List<ResultDocument.TextPoint> points(List<AnalysisOutput.TextPoint> points, Map<String, Integer> sources) {
        return points.stream().map(point -> {
            origin(point.origin()); references(point.sourceSegmentIds(), sources, "SOURCE".equals(point.origin()));
            return new ResultDocument.TextPoint(text(point.text()), point.origin());
        }).toList();
    }

    private void references(List<String> references, Map<String, Integer> sources, boolean requireEvidence) {
        if (requireEvidence) require(!references.isEmpty());
        for (String reference : references) require(sources.containsKey(reference));
    }

    private List<String> sectionReferences(List<String> keys, Map<String, String> ids) {
        require(keys.stream().distinct().count() == keys.size());
        return keys.stream().map(key -> { require(ids.containsKey(key)); return ids.get(key); }).toList();
    }

    private List<ResultDocument.Keyword> keywords(List<String> names) {
        Map<String, ResultDocument.Keyword> keywords = new LinkedHashMap<>();
        for (String name : names) {
            String display = text(name).strip();
            String canonical = Normalizer.normalize(display, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            String id = UUID.nameUUIDFromBytes(("yousum.keyword.v1:" + canonical).getBytes(StandardCharsets.UTF_8)).toString();
            keywords.putIfAbsent(id, new ResultDocument.Keyword(id, display));
        }
        return List.copyOf(keywords.values());
    }

    private List<String> texts(List<String> values) { return values.stream().map(this::text).toList(); }
    private String text(String value) { require(value != null && !value.isBlank()); return value; }
    private void origin(String value) { require("SOURCE".equals(value) || "AI_SUGGESTION".equals(value)); }
    private void require(boolean valid) { if (!valid) throw new InvalidAnalysisOutputException(); }
}
