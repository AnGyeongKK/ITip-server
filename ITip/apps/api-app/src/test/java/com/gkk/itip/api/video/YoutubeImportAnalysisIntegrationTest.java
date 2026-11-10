package com.gkk.itip.api.video;

import com.gkk.itip.api.video.infrastructure.YoutubeDataClient;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient.PlaylistPage;
import com.gkk.itip.api.video.infrastructure.YoutubeDataClient.PlaylistVideo;
import com.gkk.itip.api.video.service.VideoMetadataStore;
import com.gkk.itip.api.video.exception.YoutubeLookupException;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.domain.video.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class YoutubeImportAnalysisIntegrationTest {
    static final String VIDEO_ID = "dQw4w9WgXcQ";
    static final String OTHER_ID = "9bZkp7q19f0";
    static final String PLAYLIST = "/api/v1/youtube-playlists/PL_EXAMPLE/items";
    static final PlaylistVideo VIDEO = new PlaylistVideo(VIDEO_ID, "Imported video", "Channel",
            "https://example.com/thumbnail.jpg", 123_000L, true);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired VideoRepository videos;
    @Autowired VideoMetadataStore metadata;
    @MockitoBean YoutubeDataClient youtube;

    @BeforeEach void reset() {
        jdbc.execute("truncate table videos, idempotency_requests, youtube_playlist_metadata cascade");
    }

    @Test void playlistImportPersistsMetadataBeforeCreatingAnAnalysisBatch() throws Exception {
        when(youtube.loadPlaylist("PL_EXAMPLE", null, 20)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return page(List.of(VIDEO));
        });
        mvc.perform(get(PLAYLIST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].videoId").value(VIDEO_ID));
        assertThat(jdbc.queryForObject("select title from youtube_playlist_metadata where playlist_id = ?",
                String.class, "PL_EXAMPLE")).isEqualTo("Playlist");

        assertCreatesBatch();
        assertStoredVideo();
    }

    @Test void singleVideoImportPersistsDurationBeforeCreatingAnAnalysisBatch() throws Exception {
        when(youtube.loadVideo(VIDEO_ID)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Optional.of(VIDEO);
        });
        mvc.perform(get("/api/v1/youtube-resources").param("url", "https://youtu.be/" + VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadata.durationMs").value(123_000L));
        assertCreatesBatch();
        assertStoredVideo();
    }

    @Test void fillsDurationOfAnExistingPlaceholderVideo() throws Exception {
        videos.saveAndFlush(new Video(VIDEO_ID));
        when(youtube.loadVideo(VIDEO_ID)).thenReturn(Optional.of(VIDEO));
        mvc.perform(get("/api/v1/youtube-resources").param("url", "https://youtu.be/" + VIDEO_ID))
                .andExpect(status().isOk());
        assertCreatesBatch();
        assertStoredVideo();
    }

    @Test void cachedVideoWithDurationDoesNotNeedAProviderCall() throws Exception {
        metadata.saveAll(List.of(VIDEO));
        mvc.perform(get("/api/v1/youtube-resources").param("url", "https://youtu.be/" + VIDEO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metadata.durationMs").value(123_000L));
        verifyNoInteractions(youtube);
    }

    @Test void singleUnavailableVideoReturns404WithoutCreatingAPartialRow() throws Exception {
        when(youtube.loadVideo(VIDEO_ID)).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/youtube-resources").param("url", "https://youtu.be/" + VIDEO_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(videos.count()).isZero();
    }

    @Test void unknownDurationAndProviderFailureAre503AndDoNotCreateAPartialRow() throws Exception {
        when(youtube.loadVideo(VIDEO_ID))
                .thenReturn(Optional.of(new PlaylistVideo(VIDEO_ID, "Unknown", null, null, null, true)))
                .thenThrow(new YoutubeLookupException(YoutubeLookupException.Failure.YOUTUBE_LOOKUP_UNAVAILABLE));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(get("/api/v1/youtube-resources").param("url", "https://youtu.be/" + VIDEO_ID))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("YOUTUBE_LOOKUP_UNAVAILABLE"));
        }
        assertThat(videos.count()).isZero();
    }

    @Test void concurrentOverlappingImportsKeepOneCompleteRowPerVideo() throws Exception {
        var other = new PlaylistVideo(OTHER_ID, "Other", "Channel", null, 1000L, true);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                metadata.saveAll(List.of(VIDEO, other));
                return null;
            });
            var second = pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                metadata.saveAll(List.of(other, VIDEO));
                return null;
            });
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(videos.count()).isEqualTo(2);
        assertStoredVideo();
    }

    @Test void importDoesNotEraseKnownMetadataWhenProviderOmitsOptionalFields() throws Exception {
        Video existing = new Video(VIDEO_ID);
        Instant publishedAt = Instant.parse("2026-11-01T00:00:00Z");
        existing.updateMetadata("Old title", "Known channel", "https://example.com/channel.jpg",
                "https://example.com/old.jpg", 100_000L, publishedAt);
        videos.saveAndFlush(existing);
        when(youtube.loadPlaylist("PL_EXAMPLE", null, 20)).thenReturn(page(List.of(
                new PlaylistVideo(VIDEO_ID, "New title", null, null, 123_000L, true))));

        mvc.perform(get(PLAYLIST)).andExpect(status().isOk());

        Video saved = videos.findById(VIDEO_ID).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("New title");
        assertThat(saved.getChannelName()).isEqualTo("Known channel");
        assertThat(saved.getChannelImageUrl()).isEqualTo("https://example.com/channel.jpg");
        assertThat(saved.getThumbnailUrl()).isEqualTo("https://example.com/old.jpg");
        assertThat(saved.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(saved.getDurationMs()).isEqualTo(123_000L);
    }

    @Test void duplicateAndRepeatedImportsKeepOneVideoRow() throws Exception {
        when(youtube.loadPlaylist("PL_EXAMPLE", null, 20)).thenReturn(page(List.of(VIDEO, VIDEO)));
        mvc.perform(get(PLAYLIST)).andExpect(status().isOk());
        mvc.perform(get(PLAYLIST)).andExpect(status().isOk());
        assertThat(videos.count()).isEqualTo(1);
        assertStoredVideo();
    }

    @Test void inaccessibleOrUnknownDurationVideosAreNotRegisteredForAnalysis() throws Exception {
        when(youtube.loadPlaylist("PL_EXAMPLE", null, 20)).thenReturn(page(List.of(
                new PlaylistVideo(VIDEO_ID, "Private", null, null, null, false),
                new PlaylistVideo(OTHER_ID, "Live or unknown", null, null, null, true))));
        mvc.perform(get(PLAYLIST)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eligibility").value("INELIGIBLE"))
                .andExpect(jsonPath("$.data.items[1].eligibility").value("INELIGIBLE"));
        assertThat(videos.count()).isZero();
    }

    private void assertStoredVideo() {
        Video saved = videos.findById(VIDEO_ID).orElseThrow();
        assertThat(saved.getTitle()).isEqualTo(VIDEO.title());
        assertThat(saved.getChannelName()).isEqualTo(VIDEO.channelName());
        assertThat(saved.getThumbnailUrl()).isEqualTo(VIDEO.thumbnailUrl());
        assertThat(saved.getDurationMs()).isEqualTo(123_000L);
    }

    private void assertCreatesBatch() throws Exception {
        mvc.perform(post("/api/v1/analysis-batches")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"videoIds\":[\"" + VIDEO_ID + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].action").value("COLLECT_CAPTIONS"));
    }

    private static PlaylistPage page(List<PlaylistVideo> items) {
        return new PlaylistPage("Playlist", "Owner", null, items.size(), items, null);
    }
}
