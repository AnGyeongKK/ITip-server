package com.gkk.itip.worker.analysis.caption;

import com.gkk.itip.worker.analysis.output.ResultDocument;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class CaptionChunker {
    public record Chunk(UUID id, List<UUID> sectionIds, List<String> sourceSegmentIds,
                        String text, long startMs, long endMs) {
        public Chunk { sectionIds = List.copyOf(sectionIds); sourceSegmentIds = List.copyOf(sourceSegmentIds); }
    }

    public List<Chunk> split(UUID analysisId, CaptionDocument captions, List<ResultDocument.Section> sections, int targetCodePoints) {
        if (targetCodePoints <= 0) throw new IllegalArgumentException("positive chunk size required");
        List<Chunk> chunks = new ArrayList<>();
        var current = new Accumulator();
        for (var segment : captions.segments()) {
            int[] codePoints = segment.text().codePoints().toArray();
            for (int offset = 0; offset < codePoints.length; offset += targetCodePoints) {
                int length = Math.min(targetCodePoints, codePoints.length - offset);
                String part = new String(codePoints, offset, length);
                if (part.isBlank()) continue;
                if (!current.isEmpty() && current.codePoints + 1 + length > targetCodePoints) {
                    chunks.add(current.finish(analysisId, chunks.size(), sections));
                    current = new Accumulator();
                }
                current.append(part, length, segment);
            }
        }
        if (!current.isEmpty()) chunks.add(current.finish(analysisId, chunks.size(), sections));
        return List.copyOf(chunks);
    }

    private static class Accumulator {
        private final StringBuilder text = new StringBuilder();
        private final Set<String> sources = new LinkedHashSet<>();
        private int codePoints;
        private long start = Long.MAX_VALUE, end;

        boolean isEmpty() { return text.isEmpty(); }

        void append(String part, int length, CaptionDocument.Segment source) {
            if (!isEmpty()) { text.append('\n'); codePoints++; }
            text.append(part); codePoints += length;
            sources.add(source.segmentId());
            start = Math.min(start, source.startMs());
            end = Math.max(end, source.endMs());
        }

        Chunk finish(UUID analysisId, int index, List<ResultDocument.Section> sections) {
            String value = text.toString();
            UUID id = UUID.nameUUIDFromBytes(("yousum.caption.chunk.v1:" + analysisId + ":" + index + ":" + value)
                    .getBytes(StandardCharsets.UTF_8));
            List<UUID> sectionIds = sections.stream().filter(section -> start < section.endMs() && section.startMs() < end)
                    .map(section -> UUID.fromString(section.sectionId())).toList();
            return new Chunk(id, sectionIds, List.copyOf(sources), value, start, end);
        }
    }
}
