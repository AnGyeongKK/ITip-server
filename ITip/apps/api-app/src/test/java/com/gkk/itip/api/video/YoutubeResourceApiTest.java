package com.gkk.itip.api.video;

import com.gkk.itip.api.video.controller.YoutubeResourceController;
import com.gkk.itip.api.video.service.YoutubeResourceService;
import com.gkk.itip.api.video.service.VideoMetadataStore;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient;
import com.gkk.itip.api.video.exception.YoutubeExceptionHandler;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.web.ApiExceptionHandler;
import com.gkk.itip.domain.analysis.AnalysisJob;
import com.gkk.itip.domain.analysis.AnalysisJobRepository;
import com.gkk.itip.domain.analysis.AnalysisRecord;
import com.gkk.itip.domain.analysis.AnalysisRecordRepository;
import com.gkk.itip.domain.analysis.AnalysisResult;
import com.gkk.itip.domain.analysis.JobStatus;
import com.gkk.itip.domain.video.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YoutubeResourceApiTest {

    private static final String PATH = "/api/v1/youtube-resources";
    private static final String OWNER = "demo-import-user";
    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=" + VIDEO_ID;
    private static final Set<JobStatus> ACTIVE_JOBS =
            Set.of(JobStatus.QUEUED, JobStatus.PROCESSING, JobStatus.RETRY_WAIT);

    private final VideoRepository videos = mock(VideoRepository.class);
    private final AnalysisRecordRepository records = mock(AnalysisRecordRepository.class);
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private MockMvc mvc;
    private final YoutubeDataClient youtube = mock(YoutubeDataClient.class);
    private final VideoMetadataStore metadata = mock(VideoMetadataStore.class);

    @BeforeEach
    void setUp() {
        var controller = new YoutubeResourceController(new YoutubeResourceService(videos, records, jobs, youtube, metadata),
                new DemoUserProvider(OWNER));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler(), new YoutubeExceptionHandler())
                .build();
    }

    @Test
    void playlistResponseContainsOnlyPlaylistFields() throws Exception {
        mvc.perform(get(PATH).param("url", "https://youtube.com/playlist?list=PL_EXAMPLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.type").value("PLAYLIST"))
                .andExpect(jsonPath("$.data.playlistId").value("PL_EXAMPLE"))
                .andExpect(jsonPath("$.data.canonicalUrl").value("https://www.youtube.com/playlist?list=PL_EXAMPLE"))
                .andExpect(jsonPath("$.data.videoId").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data.existing").doesNotHaveJsonPath());

        verifyNoInteractions(videos, records, jobs);
    }

    @Test
    void returnsTheDemoOwnersCompletedRecordForAMixedLink() throws Exception {
        var now = Instant.parse("2026-11-01T00:00:00Z");
        var result = AnalysisResult.draft(UUID.randomUUID(), OWNER, VIDEO_ID, UUID.randomUUID(), "{}", now);
        result.markReady(now);
        var record = AnalysisRecord.fromCompleted(result);
        when(records.findFirstByOwnerIdAndVideoIdOrderByAnalyzedAtDescIdDesc(OWNER, VIDEO_ID))
                .thenReturn(Optional.of(record));
        givenCachedVideo(VIDEO_ID);

        mvc.perform(get(PATH).param("url", VIDEO_URL + "&list=PL_EXAMPLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("VIDEO"))
                .andExpect(jsonPath("$.data.videoId").value(VIDEO_ID))
                .andExpect(jsonPath("$.data.canonicalUrl").value(VIDEO_URL))
                .andExpect(jsonPath("$.data.metadata").hasJsonPath())
                .andExpect(jsonPath("$.data.metadata.durationMs").value(123_000L))
                .andExpect(jsonPath("$.data.existing.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.existing.analysisId").value(result.getId().toString()))
                .andExpect(jsonPath("$.data.existing.recordId").value(record.getId().toString()))
                .andExpect(jsonPath("$.data.existing.jobId").hasJsonPath())
                .andExpect(jsonPath("$.data.existing.jobId").isEmpty());

        verifyNoInteractions(jobs);
    }

    @Test
    void mapsAnActiveJobToProcessingAndAnUnknownVideoToNone() throws Exception {
        givenCachedVideo(VIDEO_ID);
        givenCachedVideo("aaaaaaaaaaa");
        var job = AnalysisJob.queued(OWNER, UUID.randomUUID(), UUID.randomUUID(), VIDEO_ID,
                UUID.randomUUID(), 1, null, Instant.parse("2026-11-01T00:00:00Z"));
        when(jobs.findFirstByOwnerIdAndVideoIdAndStatusInOrderByCreatedAtDescIdDesc(OWNER, VIDEO_ID, ACTIVE_JOBS))
                .thenReturn(Optional.of(job));

        mvc.perform(get(PATH).param("url", "https://youtu.be/" + VIDEO_ID + "?t=60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.existing.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.existing.jobId").value(job.getId().toString()));

        mvc.perform(get(PATH).param("url", "https://youtu.be/aaaaaaaaaaa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.existing.status").value("NONE"))
                .andExpect(jsonPath("$.data.captionRequirement.language").value("ko"))
                .andExpect(jsonPath("$.data.captionRequirement.maxDurationMs").value(3_600_000));
    }

    private void givenCachedVideo(String id) {
        Video video = new Video(id);
        video.updateMetadata(null, null, null, null, 123_000L, null);
        when(videos.findById(id)).thenReturn(Optional.of(video));
    }

    @Test
    void invalidOrMissingLinksAreBadRequests() throws Exception {
        mvc.perform(get(PATH).param("url", "https://youtube.com.example.org/watch?v=" + VIDEO_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").doesNotHaveJsonPath());
        mvc.perform(get(PATH))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(videos, records, jobs);
    }
}
