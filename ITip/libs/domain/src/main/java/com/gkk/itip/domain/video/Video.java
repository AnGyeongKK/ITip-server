package com.gkk.itip.domain.video;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "videos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Video {
    @Id
    @Column(name = "video_id", length = 32)
    private String videoId;
    @Column(length = 500)
    private String title;
    @Column(length = 255)
    private String channelName;
    @Column(length = 2048)
    private String channelImageUrl;
    @Column(length = 2048)
    private String thumbnailUrl;
    private Long durationMs;
    private Instant publishedAt;

    public Video(String videoId) {
        this.videoId = Objects.requireNonNull(videoId);
    }

    public void updateMetadata(String title, String channelName, String channelImageUrl,
                               String thumbnailUrl, Long durationMs, Instant publishedAt) {
        if (durationMs != null && durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive");
        }
        this.title = title;
        this.channelName = channelName;
        this.channelImageUrl = channelImageUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.durationMs = durationMs;
        this.publishedAt = publishedAt;
    }
}
