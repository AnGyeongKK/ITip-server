package com.gkk.itip.api.video.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class YoutubeDataClientTest {

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
