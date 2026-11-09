package com.gkk.itip.api.video.service;

import com.gkk.itip.api.video.infrastructure.YoutubeDataClient.PlaylistVideo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.TreeMap;

/** YouTube에서 확인한 공개 메타데이터만 저장한다. 외부 HTTP 요청은 호출자가 먼저 끝낸다. */
@Service
public class VideoMetadataStore {
    private final JdbcTemplate jdbc;

    public VideoMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void saveAll(List<PlaylistVideo> videos) {
        // 같은 페이지의 중복 제거·잠금 순서 통일. 동시 가져오기는 PK와 ON CONFLICT로 처리한다.
        var unique = new TreeMap<String, PlaylistVideo>();
        for (var video : videos) {
            if (video.available() && video.durationMs() != null && video.durationMs() > 0) {
                unique.put(video.videoId(), video);
            }
        }
        if (unique.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO videos (video_id, title, channel_name, thumbnail_url, duration_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (video_id) DO UPDATE SET
                    title = COALESCE(EXCLUDED.title, videos.title),
                    channel_name = COALESCE(EXCLUDED.channel_name, videos.channel_name),
                    thumbnail_url = COALESCE(EXCLUDED.thumbnail_url, videos.thumbnail_url),
                    duration_ms = EXCLUDED.duration_ms
                """, unique.values().stream().map(video -> new Object[]{video.videoId(), video.title(),
                        video.channelName(), video.thumbnailUrl(), video.durationMs()}).toList());
    }
}
