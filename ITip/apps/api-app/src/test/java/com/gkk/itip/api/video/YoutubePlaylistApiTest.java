package com.gkk.itip.api.video;

import com.gkk.itip.api.video.controller.YoutubePlaylistController;
import com.gkk.itip.api.video.exception.YoutubeLookupException;
import com.gkk.itip.api.video.exception.YoutubeExceptionHandler;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient.PlaylistPage;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient.PlaylistVideo;
import com.gkk.itip.api.video.service.YoutubePlaylistService;
import com.gkk.itip.api.video.service.ExistingVideoAnalyses;
import com.gkk.itip.api.video.service.VideoMetadataStore;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.web.ApiExceptionHandler;
import com.gkk.itip.domain.analysis.AnalysisJobRepository;
import com.gkk.itip.domain.analysis.AnalysisRecordRepository;
import com.gkk.itip.domain.analysis.AnalysisBatchItem;
import com.gkk.itip.domain.analysis.AnalysisBatchItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.Set;

import static com.gkk.itip.api.video.exception.YoutubeLookupException.Failure.PLAYLIST_NOT_ACCESSIBLE;
import static com.gkk.itip.api.video.exception.YoutubeLookupException.Failure.YOUTUBE_LOOKUP_UNAVAILABLE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YoutubePlaylistApiTest {

    private static final String PATH = "/api/v1/youtube-playlists/PL_EXAMPLE/items";
    private static final String OWNER = "demo-import-user";
    private final YoutubeDataClient youtube = mock(YoutubeDataClient.class);
    private final AnalysisRecordRepository records = mock(AnalysisRecordRepository.class);
    private final AnalysisJobRepository jobs = mock(AnalysisJobRepository.class);
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    private final AnalysisBatchItemRepository items = mock(AnalysisBatchItemRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var metadata = mock(VideoMetadataStore.class);
        var existing = new ExistingVideoAnalyses(records, jobs, items, Clock.fixed(NOW, ZoneOffset.UTC));
        var service = new YoutubePlaylistService(youtube, existing, metadata);
        mvc = MockMvcBuilders.standaloneSetup(new YoutubePlaylistController(service, new DemoUserProvider(OWNER)))
                .setControllerAdvice(new ApiExceptionHandler(), new YoutubeExceptionHandler())
                .build();
    }

    @Test
    void returnsTheOriginalCountAndMarksOnlyKnownIneligibleItems() throws Exception {
        when(youtube.loadPlaylist("PL_EXAMPLE", "PAGE_TOKEN", 20)).thenReturn(
                new PlaylistPage("Playlist", "Owner channel", null, 40, List.of(
                        new PlaylistVideo("dQw4w9WgXcQ", "Short video", "Video channel", null, 1000L, true),
                        new PlaylistVideo("aaaaaaaaaaa", "Long video", null, null, 3_600_001L, true),
                        new PlaylistVideo("bbbbbbbbbbb", "Private video", null, null, null, false)
                ), "NEXT_TOKEN"));

        mvc.perform(get(PATH).param("cursor", "PAGE_TOKEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channelName").value("Owner channel"))
                .andExpect(jsonPath("$.data.totalItemCount").value(40))
                .andExpect(jsonPath("$.data.nextCursor").value("NEXT_TOKEN"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.eligibilityCounts").hasJsonPath())
                .andExpect(jsonPath("$.data.eligibilityCounts").isEmpty())
                .andExpect(jsonPath("$.data.items[0].eligibility").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.items[0].existing.status").value("NONE"))
                .andExpect(jsonPath("$.data.items[1].reasonCode").value("DURATION_EXCEEDED"))
                .andExpect(jsonPath("$.data.items[2].reasonCode").value("VIDEO_UNAVAILABLE"));

        verify(records).findLatestByOwnerAndVideoIds(OWNER, Set.of("dQw4w9WgXcQ", "aaaaaaaaaaa", "bbbbbbbbbbb"));
    }

    @Test
    void returnsTheExistingPreparationForTheMatchingPlaylistItem() throws Exception {
        String videoId = "dQw4w9WgXcQ";
        UUID batchId = UUID.randomUUID();
        when(youtube.loadPlaylist("PL_EXAMPLE", null, 20)).thenReturn(
                new PlaylistPage("Playlist", null, null, 1, List.of(
                        new PlaylistVideo(videoId, "Video", null, null, 1000L, true)), null));
        when(items.findUnexpiredPreparations(OWNER, Set.of(videoId), NOW)).thenReturn(
                List.of(AnalysisBatchItem.collectCaptions(batchId, videoId, NOW.plusSeconds(60), NOW)));

        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].existing.status").value("AWAITING_UPLOAD"))
                .andExpect(jsonPath("$.data.items[0].existing.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.data.items[0].existing.jobId").isEmpty());
    }

    @Test
    void rejectsOutOfRangeLimitWithoutCallingYoutube() throws Exception {
        mvc.perform(get(PATH).param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(youtube, records, jobs);
    }

    @Test
    void distinguishesRestrictedPlaylistsFromAProviderFailure() throws Exception {
        when(youtube.loadPlaylist("PL_EXAMPLE", null, 20))
                .thenThrow(new YoutubeLookupException(PLAYLIST_NOT_ACCESSIBLE))
                .thenThrow(new YoutubeLookupException(YOUTUBE_LOOKUP_UNAVAILABLE));

        mvc.perform(get(PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLAYLIST_NOT_ACCESSIBLE"));
        mvc.perform(get(PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("YOUTUBE_LOOKUP_UNAVAILABLE"));

        verifyNoInteractions(records, jobs);
    }
}
