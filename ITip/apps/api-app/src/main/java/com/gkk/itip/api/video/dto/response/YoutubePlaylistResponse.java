package com.gkk.itip.api.video.dto.response;

import java.util.List;

public record YoutubePlaylistResponse(String playlistId, String title, List<Item> items,
                                      String nextCursor, boolean hasNext, String thumbnailUrl,
                                      String channelName, Integer totalItemCount, EligibilityCounts eligibilityCounts) {

    public record Item(String videoId, String title, String channelName, String thumbnailUrl,
                       Long durationMs, Eligibility eligibility, String reasonCode,
                       YoutubeResourceResponse.Existing existing) {
    }

    public enum Eligibility {
        UNKNOWN, INELIGIBLE
    }

    public record EligibilityCounts(int eligible, int ineligible, int unknown) {
    }
}
