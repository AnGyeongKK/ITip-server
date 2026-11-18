package com.gkk.itip.worker.analysis.caption;

import com.gkk.itip.worker.analysis.ai.AnalysisAiException;
import com.gkk.itip.worker.analysis.output.AnalysisOutput;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.regex.Pattern;

/** 분석용 묶음만 생성한다. 검색용 CaptionChunker와 원본 시간은 변경하지 않는다. */
@Component
public class AnalysisCaptionGroups {
    private static final Pattern SPACE = Pattern.compile("(?U)\\s+");
    private static final Pattern CUE = Pattern.compile("\\[[^\\]]*\\]");
    private final JsonMapper mapper;

    public AnalysisCaptionGroups(JsonMapper mapper) { this.mapper = mapper; }
    public record Group(String groupId, List<String> originalSegmentIds, long startMs, long endMs, String text) {}
    public record Prepared(Map<String, Object> input, List<Group> groups, CaptionDocument original) {}

    public Prepared prepare(CaptionDocument captions, long durationMs, String title) {
        if (captions == null || !"ko".equals(captions.language()) || captions.videoId() == null
                || captions.videoId().isBlank() || durationMs <= 0 || durationMs > 3_600_000 || captions.segments().isEmpty())
            throw invalid();
        var cleaned = new ArrayList<CaptionDocument.Segment>();
        var seen = new HashSet<String>();
        long previous = -1;
        for (var s : captions.segments()) {
            if (s == null || s.segmentId() == null || s.segmentId().isBlank() || !seen.add(s.segmentId())
                    || s.startMs() < previous || s.startMs() < 0 || s.startMs() >= s.endMs() || s.endMs() > durationMs
                    || s.text() == null || s.text().isBlank()) throw invalid();
            previous = s.startMs();
            String text = SPACE.matcher(s.text()).replaceAll(" ").strip();
            if (!CUE.matcher(text).matches()) cleaned.add(new CaptionDocument.Segment(s.segmentId(), s.startMs(), s.endMs(), text));
        }
        if (cleaned.isEmpty()) throw invalid();
        var groups = new ArrayList<Group>();
        var pending = new ArrayList<CaptionDocument.Segment>();
        for (int i = 0; i < cleaned.size(); i++) {
            var s = cleaned.get(i);
            pending.add(s);
            long clippedEnd = i + 1 < cleaned.size() ? Math.min(s.endMs(), cleaned.get(i + 1).startMs()) : s.endMs();
            long span = clippedEnd - pending.getFirst().startMs();
            if (span >= 20_000 || span >= 10_000 && s.text().matches("(?s).*[.?!]$")) flush(groups, pending);
        }
        if (!pending.isEmpty()) flush(groups, pending);
        var input = new LinkedHashMap<String, Object>();
        input.put("videoId", captions.videoId()); input.put("language", captions.language()); input.put("durationMs", durationMs);
        if (title != null && !title.isBlank()) input.put("title", title);
        input.put("groups", groups.stream().map(g -> Map.of("groupId", g.groupId(), "text", g.text())).toList());
        return new Prepared(Collections.unmodifiableMap(input), List.copyOf(groups), captions);
    }

    private void flush(List<Group> groups, List<CaptionDocument.Segment> pending) {
        groups.add(new Group("g%06d".formatted(groups.size() + 1), pending.stream().map(CaptionDocument.Segment::segmentId).toList(),
                pending.getFirst().startMs(), pending.stream().mapToLong(CaptionDocument.Segment::endMs).max().orElseThrow(),
                String.join(" ", pending.stream().map(CaptionDocument.Segment::text).toList())));
        pending.clear();
    }

    /** 최소/최대 인용 개수는 group 단위에서 검사한다. 한 group은 원본 ID 여러 개로 펼쳐질 수 있다. */
    public AnalysisOutput expand(AnalysisOutput output, Prepared prepared) {
        var groups = new LinkedHashMap<String, Group>();
        prepared.groups().forEach(g -> groups.put(g.groupId(), g));
        var data = mapper.convertValue(output, new TypeReference<Map<String, Object>>() {});
        expandReferences(data, groups);
        @SuppressWarnings("unchecked") var sections = (List<Map<String, Object>>) data.get("sections");
        var original = prepared.original().segments();
        var order = new HashMap<String, Integer>();
        for (int i = 0; i < original.size(); i++) order.put(original.get(i).segmentId(), i);
        var groupIds = prepared.groups().stream().map(Group::groupId).toList();
        int next = 0, nextGroup = 0;
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            var endGroup = groups.get(section.get("endSegmentId"));
            int groupEnd = groupIds.indexOf(section.get("endSegmentId"));
            if (endGroup == null || nextGroup >= groupIds.size() || !groupIds.get(nextGroup).equals(section.get("startSegmentId"))
                    || groupEnd < nextGroup || i == sections.size() - 1 && groupEnd != groupIds.size() - 1)
                throw new AnalysisAiException("AI_INVALID_RESPONSE", false);
            int end = i == sections.size() - 1 ? original.size() - 1 : order.get(endGroup.originalSegmentIds().getLast());
            if (end < next) throw invalid();
            // 제외한 비언어 자막은 인용하지 않지만 경계에는 포함해 원본 범위를 연속으로 보존한다.
            section.put("startSegmentId", original.get(next).segmentId());
            section.put("endSegmentId", original.get(end).segmentId());
            next = end + 1;
            nextGroup = groupEnd + 1;
        }
        return mapper.convertValue(data, AnalysisOutput.class);
    }

    @SuppressWarnings("unchecked")
    private void expandReferences(Object value, Map<String, Group> groups) {
        if (value instanceof Map<?, ?> raw) {
            var map = (Map<String, Object>) raw;
            if (map.get("sourceSegmentIds") instanceof List<?> refs) {
                var expanded = new LinkedHashSet<String>();
                for (var id : refs) {
                    var group = groups.get(id);
                    if (group == null) throw invalid();
                    expanded.addAll(group.originalSegmentIds());
                }
                map.put("sourceSegmentIds", new ArrayList<>(expanded));
            }
            map.values().forEach(child -> expandReferences(child, groups));
        } else if (value instanceof List<?> list) list.forEach(child -> expandReferences(child, groups));
    }

    private AnalysisAiException invalid() { return new AnalysisAiException("AI_INVALID_INPUT", false); }
}
