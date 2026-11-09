package com.gkk.itip.api.video.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gkk.itip.api.video.exception.YoutubeLookupException;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.gkk.itip.api.video.exception.YoutubeLookupException.Failure.PLAYLIST_NOT_ACCESSIBLE;
import static com.gkk.itip.api.video.exception.YoutubeLookupException.Failure.PLAYLIST_NOT_FOUND;
import static com.gkk.itip.api.video.exception.YoutubeLookupException.Failure.YOUTUBE_LOOKUP_UNAVAILABLE;

@Component
public class YoutubeDataClient {

    private static final String BASE_URL = "https://www.googleapis.com/youtube/v3";

    private final RestClient client;
    private final boolean configured;

    @Autowired
    public YoutubeDataClient(@Value("${itip.youtube.api-key:${YOUTUBE_API_KEY:}}") String apiKey) {
        this(apiKey, productionBuilder());
    }

    YoutubeDataClient(String apiKey, RestClient.Builder builder) {
        configured = !apiKey.isBlank();
        client = builder.baseUrl(BASE_URL).defaultHeader("X-Goog-Api-Key", apiKey.strip()).build();
    }

    private static RestClient.Builder productionBuilder() {
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory);
    }

    public PlaylistPage loadPlaylist(String playlistId, String cursor, int limit) {
        if (!configured) {
            throw new YoutubeLookupException(YOUTUBE_LOOKUP_UNAVAILABLE);
        }

        var playlists = get(uri -> uri.path("/playlists").queryParam("part", "snippet,contentDetails")
                .queryParam("id", "{id}").build(playlistId), true);
        if (playlists.entries().isEmpty()) {
            throw new YoutubeLookupException(PLAYLIST_NOT_FOUND);
        }

        var playlist = playlists.entries().getFirst();
        var page = get(uri -> playlistItemsUri(uri, playlistId, cursor, limit), true);
        var ids = page.entries().stream().map(Entry::videoId).filter(Objects::nonNull).distinct().toList();
        var videos = videoDetails(ids);
        var items = page.entries().stream()
                .filter(entry -> entry.videoId() != null)
                .map(entry -> toVideo(entry, videos.get(entry.videoId())))
                .toList();
        return new PlaylistPage(playlist.snippet().title(), playlist.snippet().channelTitle(),
                playlist.snippet().thumbnailUrl(), playlist.contentDetails().itemCount(), items, page.nextPageToken());
    }

    private static URI playlistItemsUri(UriBuilder uri, String playlistId, String cursor, int limit) {
        uri.path("/playlistItems").queryParam("part", "snippet,contentDetails")
                .queryParam("playlistId", "{id}").queryParam("maxResults", limit);
        return cursor == null ? uri.build(playlistId)
                : uri.queryParam("pageToken", "{cursor}").build(playlistId, cursor);
    }

    private Map<String, Entry> videoDetails(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return get(uri -> uri.path("/videos").queryParam("part", "snippet,contentDetails")
                .queryParam("id", "{ids}").build(String.join(",", ids)), false)
                .entries().stream()
                .filter(video -> video.id() != null)
                .collect(Collectors.toMap(Entry::id, Function.identity(), (first, duplicate) -> first));
    }

    private static PlaylistVideo toVideo(Entry item, Entry video) {
        var itemSnippet = item.snippet();
        var snippet = video == null ? Snippet.EMPTY : video.snippet();
        return new PlaylistVideo(item.videoId(), fallback(snippet.title(), itemSnippet.title()),
                fallback(snippet.channelTitle(), itemSnippet.videoOwnerChannelTitle()),
                fallback(snippet.thumbnailUrl(), itemSnippet.thumbnailUrl()),
                video == null ? null : millis(video.contentDetails().duration()), video != null);
    }

    private static String fallback(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static Long millis(String duration) {
        if (duration == null) {
            return null;
        }
        try {
            long value = Duration.parse(duration).toMillis();
            return value > 0 ? value : null;
        } catch (DateTimeParseException | ArithmeticException exception) {
            return null;
        }
    }

    private Listing get(Function<UriBuilder, URI> uri, boolean playlistRequest) {
        try {
            var response = client.get().uri(uri).retrieve().body(Listing.class);
            if (response == null) {
                throw new YoutubeLookupException(YOUTUBE_LOOKUP_UNAVAILABLE);
            }
            return response;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (playlistRequest && status == 404) {
                throw new YoutubeLookupException(PLAYLIST_NOT_FOUND);
            }
            if (playlistRequest) {
                var reasons = errorReasons(exception);
                if (status == 400 && reasons.contains("invalidPageToken")) {
                    throw new BusinessException(ErrorCode.INVALID_CURSOR);
                }
                if (status == 403 && (reasons.contains("playlistItemsNotAccessible")
                        || reasons.contains("playlistForbidden"))) {
                    throw new YoutubeLookupException(PLAYLIST_NOT_ACCESSIBLE);
                }
            }
            throw new YoutubeLookupException(YOUTUBE_LOOKUP_UNAVAILABLE);
        } catch (RestClientException exception) {
            throw new YoutubeLookupException(YOUTUBE_LOOKUP_UNAVAILABLE);
        }
    }

    private static List<String> errorReasons(RestClientResponseException exception) {
        try {
            var response = exception.getResponseBodyAs(GoogleError.class);
            if (response != null && response.error() != null && response.error().errors() != null) {
                return response.error().errors().stream().map(ErrorReason::reason).toList();
            }
        } catch (RestClientException | IllegalStateException ignored) {
            // The upstream response may not contain JSON.
        }
        return List.of();
    }

    public record PlaylistPage(String title, String channelName, String thumbnailUrl,
                               Integer totalItemCount, List<PlaylistVideo> items, String nextCursor) {
    }

    public record PlaylistVideo(String videoId, String title, String channelName, String thumbnailUrl,
                                Long durationMs, boolean available) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Listing(List<Entry> items, String nextPageToken) {
        List<Entry> entries() {
            return items == null ? List.of() : items;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(String id, Snippet snippet, Details contentDetails) {
        private Entry {
            snippet = snippet == null ? Snippet.EMPTY : snippet;
            contentDetails = contentDetails == null ? new Details(null, null, null) : contentDetails;
        }

        String videoId() {
            return contentDetails.videoId();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Snippet(String title, String channelTitle, String videoOwnerChannelTitle,
                           Map<String, Thumbnail> thumbnails) {
        private static final Snippet EMPTY = new Snippet(null, null, null, Map.of());

        String thumbnailUrl() {
            if (thumbnails == null) {
                return null;
            }
            for (String size : List.of("maxres", "standard", "high", "medium", "default")) {
                var thumbnail = thumbnails.get(size);
                if (thumbnail != null && thumbnail.url() != null) {
                    return thumbnail.url();
                }
            }
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Thumbnail(String url) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Details(String videoId, String duration, Integer itemCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleError(ErrorBody error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorBody(List<ErrorReason> errors) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorReason(String reason) {
    }
}
