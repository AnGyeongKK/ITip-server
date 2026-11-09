package com.gkk.itip.api.video.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class YoutubeUrlParser {

    private static final Set<String> YOUTUBE_HOSTS =
            Set.of("youtube.com", "www.youtube.com", "m.youtube.com");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private YoutubeUrlParser() {
    }

    public static Resource parse(String url) {
        if (url == null || url.isBlank()) {
            throw invalidUrl();
        }

        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException exception) {
            throw invalidUrl();
        }

        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        if ((!https && !http) || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw invalidUrl();
        }
        if (uri.getPort() != -1 && uri.getPort() != (https ? 443 : 80)) {
            throw invalidUrl();
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if ("youtu.be".equals(host)) {
            return video(path.startsWith("/") ? path.substring(1) : path);
        }
        if (!YOUTUBE_HOSTS.contains(host)) {
            throw invalidUrl();
        }

        return switch (path) {
            case "/watch" -> video(queryParameter(uri, "v"));
            case "/playlist" -> playlist(queryParameter(uri, "list"));
            default -> throw invalidUrl();
        };
    }

    private static String queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            throw invalidUrl();
        }

        String value = null;
        for (String parameter : query.split("&")) {
            String[] pair = parameter.split("=", 2);
            if (!name.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                continue;
            }
            if (value != null || pair.length != 2) {
                throw invalidUrl();
            }
            value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        if (value == null) {
            throw invalidUrl();
        }
        return value;
    }

    private static Resource video(String id) {
        if (!VIDEO_ID.matcher(id).matches()) {
            throw invalidUrl();
        }
        return new Resource(Type.VIDEO, id, "https://www.youtube.com/watch?v=" + id);
    }

    private static Resource playlist(String id) {
        if (!isPlaylistId(id)) {
            throw invalidUrl();
        }
        return new Resource(Type.PLAYLIST, id, "https://www.youtube.com/playlist?list=" + id);
    }

    static boolean isPlaylistId(String id) {
        return id != null && PLAYLIST_ID.matcher(id).matches();
    }

    private static IllegalArgumentException invalidUrl() {
        return new IllegalArgumentException("Invalid YouTube video or playlist URL");
    }

    public enum Type {
        VIDEO, PLAYLIST
    }

    public record Resource(Type type, String id, String canonicalUrl) {
    }
}
