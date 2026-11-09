package com.gkk.itip.api.video.service;

import com.gkk.itip.api.video.dto.response.YoutubePlaylistResponse;
import com.gkk.itip.api.video.dto.response.YoutubePlaylistResponse.Eligibility;
import com.gkk.itip.api.video.dto.response.YoutubePlaylistResponse.Item;
import com.gkk.itip.api.video.dto.response.YoutubeResourceResponse.Existing;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient.PlaylistVideo;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Service
public class YoutubePlaylistService {

    private static final int MAX_LIMIT = 50;
    private final YoutubeDataClient youtube;
    private final YoutubeResourceService resources;
    private final VideoMetadataStore metadata;

    public YoutubePlaylistService(YoutubeDataClient youtube, YoutubeResourceService resources, VideoMetadataStore metadata) {
        this.youtube = youtube;
        this.resources = resources;
        this.metadata = metadata;
    }

    public YoutubePlaylistResponse findItems(String ownerId, String playlistId, String cursor, int limit) {
        if (!YoutubeUrlParser.isPlaylistId(playlistId) || limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (cursor != null && cursor.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_CURSOR);
        }

        var page = youtube.loadPlaylist(playlistId, cursor, limit);
        metadata.saveAll(page.items());
        var ids = page.items().stream().map(PlaylistVideo::videoId).collect(Collectors.toSet());
        var existing = resources.existingAnalyses(ownerId, ids);
        var items = page.items().stream().map(video -> toItem(video, existing)).toList();
        return new YoutubePlaylistResponse(playlistId, page.title(), items, page.nextCursor(), page.nextCursor() != null,
                page.thumbnailUrl(), page.channelName(), page.totalItemCount(), null);
    }

    private static Item toItem(PlaylistVideo video, Map<String, Existing> existing) {
        String reason = null;
        if (!video.available() || video.durationMs() == null || video.durationMs() <= 0) {
            reason = "VIDEO_UNAVAILABLE";
        } else if (video.durationMs() != null && video.durationMs() > YoutubeResourceService.MAX_DURATION_MS) {
            reason = "DURATION_EXCEEDED";
        }
        return new Item(video.videoId(), video.title(), video.channelName(), video.thumbnailUrl(), video.durationMs(),
                reason == null ? Eligibility.UNKNOWN : Eligibility.INELIGIBLE, reason,
                existing.getOrDefault(video.videoId(), YoutubeResourceService.NO_ANALYSIS));
    }
}
