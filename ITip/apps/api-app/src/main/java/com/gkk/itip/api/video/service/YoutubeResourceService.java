package com.gkk.itip.api.video.service;

import com.gkk.itip.api.video.dto.response.YoutubeResourceResponse;
import com.gkk.itip.api.video.dto.response.YoutubeResourceResponse.Metadata;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.exception.ErrorCode;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient;
import com.gkk.itip.api.video.exception.YoutubeLookupException;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.domain.video.VideoRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

@Service
public class YoutubeResourceService {

    static final long MAX_DURATION_MS = Duration.ofHours(1).toMillis();
    private static final YoutubeResourceResponse.CaptionRequirement CAPTION_REQUIREMENT =
            new YoutubeResourceResponse.CaptionRequirement("ko", MAX_DURATION_MS);

    private final VideoRepository videos;
    private final ExistingVideoAnalyses existingAnalyses;
    private final YoutubeDataClient youtube;
    private final VideoMetadataStore metadata;

    public YoutubeResourceService(VideoRepository videos, ExistingVideoAnalyses existingAnalyses,
                                  YoutubeDataClient youtube, VideoMetadataStore metadata) {
        this.videos = videos;
        this.existingAnalyses = existingAnalyses;
        this.youtube = youtube;
        this.metadata = metadata;
    }

    public YoutubeResourceResponse resolve(String ownerId, String url) {
        YoutubeUrlParser.Resource resource;
        try {
            resource = YoutubeUrlParser.parse(url);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "YouTube 영상 또는 재생목록 링크를 입력해 주세요.");
        }
        if (resource.type() == YoutubeUrlParser.Type.PLAYLIST) {
            return new YoutubeResourceResponse.Playlist(resource.type().name(), resource.id(), resource.canonicalUrl());
        }

        var video = videos.findById(resource.id());
        if (video.isEmpty() || video.get().getDurationMs() == null) {
            // 메타데이터 HTTP 조회 중 DB 트랜잭션을 유지하지 않는다.
            var details = youtube.loadVideo(resource.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (details.durationMs() == null || details.durationMs() <= 0) {
                throw new YoutubeLookupException(YoutubeLookupException.Failure.YOUTUBE_LOOKUP_UNAVAILABLE);
            }
            metadata.saveAll(List.of(details));
            video = videos.findById(resource.id());
        }
        Metadata metadata = video.map(YoutubeResourceService::metadataOf).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new YoutubeResourceResponse.Video(resource.type().name(), resource.id(), resource.canonicalUrl(),
                metadata, existingAnalyses.find(ownerId, Set.of(resource.id()))
                        .getOrDefault(resource.id(), ExistingVideoAnalyses.NONE), CAPTION_REQUIREMENT);
    }

    private static Metadata metadataOf(Video video) {
        if (video.getTitle() == null && video.getChannelName() == null && video.getThumbnailUrl() == null
                && video.getDurationMs() == null && video.getChannelImageUrl() == null && video.getPublishedAt() == null) {
            return null;
        }
        return new Metadata(video.getVideoId(), video.getTitle(), video.getChannelName(), video.getThumbnailUrl(),
                video.getDurationMs(), video.getChannelImageUrl(), video.getPublishedAt());
    }
}
