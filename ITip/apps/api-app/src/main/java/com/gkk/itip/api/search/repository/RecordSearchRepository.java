package com.gkk.itip.api.search.repository;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse.VideoInfo;
import com.gkk.itip.api.archive.repository.PlaylistGroupRepository;
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
            with recursive scope as (
                select ?::uuid as folder_id, ?::uuid as target_folder_id, ?::varchar as playlist_id,
                       ?::varchar as owner_id, false as all_playlists
            ),
            """ + PlaylistGroupRepository.MEMBER_CTES + """
            select record_id, analysis_id, analyzed_at, result_json, latest.video_id, v.title, v.channel_name,
                   v.channel_image_url, v.thumbnail_url, v.published_at, v.duration_ms, included
            from (
                select distinct on (scope_key) r.video_id, r.id as record_id, r.analysis_id, r.analyzed_at, a.result_json,
                       case when scope.folder_id is null and scope.playlist_id is null
                            then r.video_id else r.id::text end as scope_key,
                       exists(select 1 from folder_records fr where fr.folder_id = scope.target_folder_id
                              and fr.record_id = r.id and fr.owner_id = r.owner_id) as included
                from analysis_records r
                join analysis_results a on a.id = r.analysis_id and a.owner_id = r.owner_id and a.video_id = r.video_id
                join scope on r.owner_id = scope.owner_id
                where a.chat_ready
                  and (scope.folder_id is null or exists(
                      select 1 from folder_records fr where fr.folder_id = scope.folder_id
                      and fr.record_id = r.id and fr.owner_id = r.owner_id))
                  and (scope.playlist_id is null or exists(
                      select 1 from playlist_members p where p.record_id = r.id))
                order by scope_key, r.analyzed_at desc, r.id desc
            ) latest
            join videos v on v.video_id = latest.video_id
            order by analyzed_at desc, record_id desc
            """;

    private final JdbcTemplate jdbc;

    public RecordSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void scanCompleted(String ownerId, UUID folderId, UUID targetFolderId, String playlistId,
                              Consumer<StoredRecord> consumer) {
        // The service opens a read transaction so PostgreSQL can fetch the result in batches.
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(COMPLETED_RECORDS);
            statement.setObject(1, folderId);
            statement.setObject(2, targetFolderId);
            statement.setString(3, playlistId);
            statement.setString(4, ownerId);
            statement.setFetchSize(100);
            return statement;
        }, (ResultSet row) -> {
            var publishedAt = row.getObject("published_at", OffsetDateTime.class);
            var video = new VideoInfo(row.getString("video_id"), row.getString("title"), row.getString("channel_name"),
                    row.getString("channel_image_url"), row.getString("thumbnail_url"),
                    publishedAt == null ? null : publishedAt.toInstant(), row.getObject("duration_ms", Long.class));
            consumer.accept(new StoredRecord(row.getObject("record_id", UUID.class),
                    row.getObject("analysis_id", UUID.class), row.getObject("analyzed_at", OffsetDateTime.class).toInstant(),
                    video, row.getString("result_json"), row.getBoolean("included")));
        });
    }

    public record StoredRecord(UUID recordId, UUID analysisId, Instant analyzedAt, VideoInfo video, String resultJson,
                               boolean included) {}
}
