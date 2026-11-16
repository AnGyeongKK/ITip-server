package com.gkk.itip.api.video.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

public sealed interface YoutubeResourceResponse {

    record Video(String type, String videoId, String canonicalUrl, Metadata metadata,
                 Existing existing, CaptionRequirement captionRequirement) implements YoutubeResourceResponse {
    }

    record Playlist(String type, String playlistId, String canonicalUrl) implements YoutubeResourceResponse {
    }

    record Metadata(String videoId, String title, String channelName, String thumbnailUrl,
                    Long durationMs, String channelImageUrl, Instant publishedAt) {
    }

    record Existing(Status status, UUID analysisId, UUID recordId, UUID jobId,
                    @JsonInclude(JsonInclude.Include.NON_NULL) UUID batchId) {
        public enum Status {
            NONE, AWAITING_UPLOAD, PROCESSING, COMPLETED
        }
    }

    record CaptionRequirement(String language, long maxDurationMs) {
    }
}
