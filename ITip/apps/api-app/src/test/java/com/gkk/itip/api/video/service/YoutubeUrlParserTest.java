package com.gkk.itip.api.video.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YoutubeUrlParserTest {

    private static final String VIDEO_ID = "dQw4w9WgXcQ";
    private static final String VIDEO_URL = "https://www.youtube.com/watch?v=" + VIDEO_ID;

    @Test
    void normalizesVideoLinksToTheSameResource() {
        var expected = new YoutubeUrlParser.Resource(YoutubeUrlParser.Type.VIDEO, VIDEO_ID, VIDEO_URL);
        var links = List.of(
                VIDEO_URL,
                "https://youtu.be/" + VIDEO_ID + "?t=45",
                " https://m.youtube.com/watch?t=45&v=dQw4w9WgXcQ ",
                "https://www.youtube.com/watch?v=%64Qw4w9WgXcQ"
        );

        for (String link : links) {
            assertEquals(expected, YoutubeUrlParser.parse(link), link);
        }
    }

    @Test
    void aWatchLinkWithAPlaylistParameterStillIdentifiesTheVideo() {
        var resource = YoutubeUrlParser.parse(VIDEO_URL + "&list=PL_EXAMPLE");

        assertEquals(new YoutubeUrlParser.Resource(YoutubeUrlParser.Type.VIDEO, VIDEO_ID, VIDEO_URL),
                resource);
    }

    @Test
    void recognizesAPlaylistOnlyLink() {
        var resource = YoutubeUrlParser.parse("https://youtube.com/playlist?list=PL_EXAMPLE&si=share");

        assertEquals(new YoutubeUrlParser.Resource(YoutubeUrlParser.Type.PLAYLIST, "PL_EXAMPLE",
                "https://www.youtube.com/playlist?list=PL_EXAMPLE"), resource);
    }

    @Test
    void rejectsInvalidAndAmbiguousLinks() {
        var links = List.of(
                "",
                "not a URL",
                "//www.youtube.com/watch?v=" + VIDEO_ID,
                "https://youtube.com.example.org/watch?v=" + VIDEO_ID,
                "https://other.example@youtube.com/watch?v=" + VIDEO_ID,
                "https://youtube.com:8443/watch?v=" + VIDEO_ID,
                "https://youtube.com/watch?list=PL_EXAMPLE",
                VIDEO_URL + "&v=aaaaaaaaaaa",
                "https://youtube.com/watch?v=",
                "https://youtube.com/playlist?list=PL_EXAMPLE%26other",
                "https://youtu.be/" + VIDEO_ID + "/extra"
        );

        for (String link : links) {
            assertThrows(IllegalArgumentException.class, () -> YoutubeUrlParser.parse(link), link);
        }
        assertThrows(IllegalArgumentException.class, () -> YoutubeUrlParser.parse(null));
    }
}
