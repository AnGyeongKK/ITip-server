package com.gkk.itip.api.analysis.dto.response;

import com.gkk.itip.domain.analysis.result.ResultDocument;
import com.gkk.itip.domain.video.Video;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnalysisResultResponse(UUID analysisId, UUID recordId, String status, String schemaVersion,
                                     VideoInfo video, ResultDocument.Summary summary, List<SectionItem> sections,
                                     ResultDocument.Mindmap mindmap, boolean chatReady, Instant analyzedAt) {
    public record VideoInfo(String videoId, String title, String channelName, String channelImageUrl,
                            String thumbnailUrl, Instant publishedAt, Long durationMs) {
        public static VideoInfo from(Video video) {
            return new VideoInfo(video.getVideoId(), video.getTitle(), video.getChannelName(), video.getChannelImageUrl(),
                    video.getThumbnailUrl(), video.getPublishedAt(), video.getDurationMs());
        }
    }

    public record SectionItem(String sectionId, String title, long startMs, long endMs, String summary,
                              List<String> subtopics, List<ResultDocument.Keyword> keywords) {
        public static SectionItem from(ResultDocument.Section section) {
            return new SectionItem(section.sectionId(), section.title(), section.startMs(), section.endMs(),
                    section.summary(), section.subtopics(), section.keywords());
        }
    }
}
