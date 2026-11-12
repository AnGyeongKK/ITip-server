package com.gkk.itip.api.video.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import com.gkk.itip.api.video.exception.YoutubeLookupException;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.exception.ErrorCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;

class YoutubeDataClientTest {

    @Test void loadsSingleVideoMetadataAndTreatsMissingVideosAsEmpty() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new YoutubeDataClient("test-key", builder);
        server.expect(queryParam("id", "dQw4w9WgXcQ")).andRespond(withSuccess("""
                {"items":[{"id":"dQw4w9WgXcQ","snippet":{"title":"Video","channelTitle":"Channel"},
                "contentDetails":{"duration":"PT2M3S"}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(queryParam("id", "aaaaaaaaaaa"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.loadVideo("dQw4w9WgXcQ").orElseThrow().durationMs()).isEqualTo(123_000L);
        assertThat(client.loadVideo("aaaaaaaaaaa")).isEmpty();
        server.verify();
    }

    @Test void mapsInvalidPageTokensToThePublicCursorError() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new YoutubeDataClient("test-key", builder);
        expectPlaylist(server);
        server.expect(requestTo(startsWith("https://www.googleapis.com/youtube/v3/playlistItems?")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"errors\":[{\"reason\":\"invalidPageToken\"}]}}"));
        assertThatThrownBy(() -> client.loadPlaylist("PL_EXAMPLE", "bad-token", 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURSOR));
        server.verify();
    }

    @Test void mapsRestrictedPlaylistsAndTimeoutsWithoutExposingTheProviderResponse() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new YoutubeDataClient("test-key", builder);
        expectPlaylist(server);
        server.expect(requestTo(startsWith("https://www.googleapis.com/youtube/v3/playlistItems?")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"errors\":[{\"reason\":\"playlistItemsNotAccessible\"}]}}"));
        server.expect(requestTo(startsWith("https://www.googleapis.com/youtube/v3/playlists?")))
                .andRespond(withException(new SocketTimeoutException("private provider detail")));
        assertThatThrownBy(() -> client.loadPlaylist("PL_EXAMPLE", null, 20))
                .isInstanceOfSatisfying(YoutubeLookupException.class,
                        e -> assertThat(e.code()).isEqualTo("PLAYLIST_NOT_ACCESSIBLE"));
        assertThatThrownBy(() -> client.loadPlaylist("PL_EXAMPLE", null, 20))
                .isInstanceOfSatisfying(YoutubeLookupException.class, e -> {
                    assertThat(e.code()).isEqualTo("YOUTUBE_LOOKUP_UNAVAILABLE");
                    assertThat(e.getMessage()).doesNotContain("private provider detail");
                });
        server.verify();
    }

    private static void expectPlaylist(MockRestServiceServer server) {
        server.expect(requestTo(startsWith("https://www.googleapis.com/youtube/v3/playlists?")))
                .andRespond(withSuccess("{\"items\":[{\"snippet\":{\"title\":\"List\"}}]}", MediaType.APPLICATION_JSON));
    }

    @Test
    void loadsPlaylistMetadataAndVideoDetailsUsingTheOriginalPageToken() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new YoutubeDataClient("test-key", builder);

        server.expect(requestTo(startsWith("https://www.googleapis.com/youtube/v3/playlists?")))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andExpect(queryParam("id", "PL_EXAMPLE"))
                .andRespond(withSuccess("""
                        {"items":[{"snippet":{"title":"Playlist","channelTitle":"Playlist owner"},
                          "contentDetails":{"itemCount":99}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://www.googleapis.com/youtube/v3/playlistItems?")))
                .andExpect(queryParam("playlistId", "PL_EXAMPLE"))
                .andExpect(queryParam("pageToken", "PAGE_TOKEN"))
                .andExpect(queryParam("maxResults", "20"))
                .andRespond(withSuccess("""
                        {"nextPageToken":"NEXT_TOKEN","items":[{"snippet":{"title":"Item"},
                          "contentDetails":{"videoId":"dQw4w9WgXcQ"}}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("https://www.googleapis.com/youtube/v3/videos?")))
                .andExpect(queryParam("id", "dQw4w9WgXcQ"))
                .andRespond(withSuccess("""
                        {"items":[{"id":"dQw4w9WgXcQ","snippet":{"title":"Video","channelTitle":"Video owner"},
                          "contentDetails":{"duration":"PT2M3S"}}]}
                        """, MediaType.APPLICATION_JSON));

        var page = client.loadPlaylist("PL_EXAMPLE", "PAGE_TOKEN", 20);

        assertEquals("Playlist owner", page.channelName());
        assertEquals(99, page.totalItemCount());
        assertEquals("NEXT_TOKEN", page.nextCursor());
        assertEquals("Video owner", page.items().getFirst().channelName());
        assertEquals(123_000L, page.items().getFirst().durationMs());
        assertTrue(page.items().getFirst().available());
        server.verify();
    }
}
