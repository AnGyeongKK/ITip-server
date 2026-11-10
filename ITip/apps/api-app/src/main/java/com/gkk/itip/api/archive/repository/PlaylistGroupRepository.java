package com.gkk.itip.api.archive.repository;

import com.gkk.itip.api.archive.dto.response.PlaylistGroupResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PlaylistGroupRepository {
    // Follow the jobs selected by playlist batches, including their manual retries.
    public static final String MEMBER_CTES = """
            playlist_items as (
                select b.playlist_id, i.video_id, i.job_id, i.record_id, i.analysis_id
                from scope
                join analysis_batches b on b.owner_id = scope.owner_id
                join analysis_batch_items i on i.batch_id = b.id
                where btrim(b.playlist_id) <> '' and (scope.all_playlists or b.playlist_id = scope.playlist_id)
            ),
            playlist_jobs(playlist_id, video_id, job_id) as (
                select playlist_id, video_id, job_id from playlist_items where job_id is not null
                union
                select p.playlist_id, p.video_id, j.id
                from playlist_jobs p
                join analysis_jobs j on j.previous_job_id = p.job_id and j.video_id = p.video_id
                join scope on j.owner_id = scope.owner_id
            ),
            playlist_links as (
                select playlist_id, video_id, record_id, analysis_id
                from playlist_items where record_id is not null
                union all
                select p.playlist_id, p.video_id, j.record_id, j.analysis_id
                from playlist_jobs p
                join analysis_jobs j on j.id = p.job_id and j.video_id = p.video_id
                join scope on j.owner_id = scope.owner_id
                where j.status = 'COMPLETED'
            ),
            playlist_members as (
                select distinct p.playlist_id, r.id as record_id, r.analyzed_at
                from playlist_links p
                join analysis_records r on r.id = p.record_id and r.video_id = p.video_id and r.analysis_id = p.analysis_id
                join scope on r.owner_id = scope.owner_id
                join analysis_results a on a.id = r.analysis_id and a.owner_id = r.owner_id
                  and a.video_id = r.video_id and a.chat_ready
            )
            """;
    private static final String GROUPS = """
            with recursive scope as (
                select ?::varchar as owner_id, null::varchar as playlist_id, true as all_playlists
            ),
            """ + MEMBER_CTES + """
            , groups as (
                select md5(jsonb_build_array((select owner_id from scope), playlist_id)::text)::uuid as group_id,
                       playlist_id, count(*) as record_count, max(analyzed_at) as last_analyzed_at
                from playlist_members
                group by playlist_id
            )
            select group_id, playlist_id, record_count, last_analyzed_at, metadata.title
            from groups
            left join youtube_playlist_metadata metadata using (playlist_id)
            """;
    private static final RowMapper<PlaylistGroupResponse> GROUP = (row, index) -> {
        String playlistId = row.getString("playlist_id");
        String originalUrl = UriComponentsBuilder.fromUriString("https://www.youtube.com/playlist")
                .queryParam("list", "{id}").encode().buildAndExpand(playlistId).toUriString();
        return new PlaylistGroupResponse(row.getObject("group_id", UUID.class), playlistId, row.getString("title"), originalUrl,
                row.getLong("record_count"), row.getObject("last_analyzed_at", OffsetDateTime.class).toInstant());
    };

    private final JdbcTemplate jdbc;

    public PlaylistGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PlaylistGroupResponse> list(String ownerId, OffsetDateTime before, UUID beforeId, int limit) {
        return jdbc.query(GROUPS + """
                where (?::timestamptz is null or (last_analyzed_at, group_id) < (?, ?))
                order by last_analyzed_at desc, group_id desc
                limit ?
                """, GROUP, ownerId, before, before, beforeId, limit);
    }

    public Optional<PlaylistGroupResponse> find(String ownerId, UUID groupId) {
        return jdbc.query(GROUPS + "where group_id = ?", GROUP, ownerId, groupId).stream().findFirst();
    }
}
