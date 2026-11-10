package com.gkk.itip.api.search.repository;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse.VideoInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
public class RecordSearchRepository {
    private static final String COMPLETED_RECORDS = """
            select record_id, analysis_id, analyzed_at, result_json, latest.video_id, v.title, v.channel_name,
                   v.channel_image_url, v.thumbnail_url, v.published_at, v.duration_ms
            from (
                select distinct on (r.video_id) r.video_id, r.id as record_id, r.analysis_id, r.analyzed_at, a.result_json
                from analysis_records r
                join analysis_results a on a.id = r.analysis_id and a.owner_id = r.owner_id and a.video_id = r.video_id
                where r.owner_id = ? and a.chat_ready
                order by r.video_id, r.analyzed_at desc, r.id desc
            ) latest
            join videos v on v.video_id = latest.video_id
            order by analyzed_at desc, record_id desc
            """;

    private final JdbcTemplate jdbc;

    public RecordSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void scanCompleted(String ownerId, Consumer<StoredRecord> consumer) {
        // The service opens a read transaction so PostgreSQL can fetch the result in batches.
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(COMPLETED_RECORDS);
            statement.setString(1, ownerId);
            statement.setFetchSize(100);
            return statement;
        }, (ResultSet row) -> {
            var publishedAt = row.getObject("published_at", OffsetDateTime.class);
            var video = new VideoInfo(row.getString("video_id"), row.getString("title"), row.getString("channel_name"),
                    row.getString("channel_image_url"), row.getString("thumbnail_url"),
                    publishedAt == null ? null : publishedAt.toInstant(), row.getObject("duration_ms", Long.class));
            consumer.accept(new StoredRecord(row.getObject("record_id", UUID.class),
                    row.getObject("analysis_id", UUID.class), row.getObject("analyzed_at", OffsetDateTime.class).toInstant(),
                    video, row.getString("result_json")));
        });
    }

    public record StoredRecord(UUID recordId, UUID analysisId, Instant analyzedAt, VideoInfo video, String resultJson) {}
}
