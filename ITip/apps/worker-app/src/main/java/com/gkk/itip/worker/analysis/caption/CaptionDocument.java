package com.gkk.itip.worker.analysis.caption;

import java.util.List;

public record CaptionDocument(String videoId, String language, List<Segment> segments) {
    public CaptionDocument { segments = List.copyOf(segments); }

    public record Segment(String segmentId, long startMs, long endMs, String text) {}
}
