package com.gkk.itip.api.video.service;

import com.gkk.itip.api.video.dto.response.YoutubeResourceResponse;
import com.gkk.itip.api.video.dto.response.YoutubeResourceResponse.Existing;
import com.gkk.itip.api.video.dto.response.YoutubeResourceResponse.Metadata;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.exception.ErrorCode;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient;
import com.gkk.itip.api.video.exception.YoutubeLookupException;
import com.gkk.itip.domain.analysis.AnalysisJobRepository;
import com.gkk.itip.domain.analysis.AnalysisRecordRepository;
import com.gkk.itip.domain.analysis.JobStatus;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.domain.video.VideoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

@Service
public class YoutubeResourceService {

    static final long MAX_DURATION_MS = Duration.ofHours(1).toMillis();
    static final Existing NO_ANALYSIS = new Existing(Existing.Status.NONE, null, null, null);
    private static final Set<JobStatus> ACTIVE_JOBS =
            Set.of(JobStatus.QUEUED, JobStatus.PROCESSING, JobStatus.RETRY_WAIT);
    private static final YoutubeResourceResponse.CaptionRequirement CAPTION_REQUIREMENT =
            new YoutubeResourceResponse.CaptionRequirement("ko", MAX_DURATION_MS);

    private final VideoRepository videos;
    private final AnalysisRecordRepository records;
    private final AnalysisJobRepository jobs;
    private final YoutubeDataClient youtube;
    private final VideoMetadataStore metadata;

    public YoutubeResourceService(VideoRepository videos, AnalysisRecordRepository records, AnalysisJobRepository jobs,
                                  YoutubeDataClient youtube, VideoMetadataStore metadata) {
        this.videos = videos;
        this.records = records;
        this.jobs = jobs;
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
                metadata, existingAnalysis(ownerId, resource.id()), CAPTION_REQUIREMENT);
    }

    private Existing existingAnalysis(String ownerId, String videoId) {
        var record = records.findFirstByOwnerIdAndVideoIdOrderByAnalyzedAtDescIdDesc(ownerId, videoId);
        if (record.isPresent()) {
            var completed = record.get();
            return new Existing(Existing.Status.COMPLETED, completed.getAnalysisId(), completed.getId(), null);
        }
        return jobs.findFirstByOwnerIdAndVideoIdAndStatusInOrderByCreatedAtDescIdDesc(ownerId, videoId, ACTIVE_JOBS)
                .map(job -> new Existing(Existing.Status.PROCESSING, null, null, job.getId()))
                .orElse(NO_ANALYSIS);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Existing> existingAnalyses(String ownerId, Set<String> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Existing> found = new HashMap<>();
        for (var record : records.findLatestByOwnerAndVideoIds(ownerId, videoIds)) {
            found.put(record.getVideoId(),
                    new Existing(Existing.Status.COMPLETED, record.getAnalysisId(), record.getId(), null));
        }
        Set<String> remaining = new HashSet<>(videoIds);
        remaining.removeAll(found.keySet());
        if (!remaining.isEmpty()) {
            for (var job : jobs.findAllByOwnerIdAndVideoIdInAndStatusIn(ownerId, remaining, ACTIVE_JOBS)) {
                found.put(job.getVideoId(), new Existing(Existing.Status.PROCESSING, null, null, job.getId()));
            }
        }
        return found;
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
