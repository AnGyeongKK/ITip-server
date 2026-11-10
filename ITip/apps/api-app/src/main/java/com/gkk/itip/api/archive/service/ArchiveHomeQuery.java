package com.gkk.itip.api.archive.service;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse.VideoInfo;
import com.gkk.itip.api.archive.dto.response.ArchiveHomeResponse;
import com.gkk.itip.api.archive.dto.response.ArchiveHomeResponse.AnalysisCard;
import com.gkk.itip.api.archive.dto.response.ArchiveHomeResponse.ContinueReading;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.analysis.result.ResultDocument;
import com.gkk.itip.domain.analysis.result.ResultDocumentValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.INTERNAL_ERROR;

@Service
public class ArchiveHomeQuery {
    private static final int RECENT_LIMIT = 5;
    private static final String CARDS = """
            select r.id, r.analysis_id, r.analyzed_at, r.reading_tab, r.reading_section_id, r.last_viewed_at,
                   v.video_id, v.title, v.channel_name, v.channel_image_url, v.thumbnail_url, v.published_at, v.duration_ms,
                   a.result_json
            from analysis_records r
            join analysis_results a on a.id = r.analysis_id and a.owner_id = r.owner_id
                and a.video_id = r.video_id and a.chat_ready
            join videos v on v.video_id = r.video_id
            where r.owner_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public ArchiveHomeQuery(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ArchiveHomeResponse get(String ownerId) {
        var recent = jdbc.query(CARDS + "order by r.analyzed_at desc, r.id desc limit ?",
                (row, index) -> card(row), ownerId, RECENT_LIMIT);
        var resume = jdbc.query(CARDS + """
                and r.last_viewed_at is not null
                and exists(select 1 from analysis_sections s
                    where s.id = r.reading_section_id and s.analysis_id = r.analysis_id)
                order by r.last_viewed_at desc, r.id desc limit 1
                """, (row, index) -> continueReading(row), ownerId).stream().findFirst().orElse(null);
        return new ArchiveHomeResponse(recent, resume);
    }

    private AnalysisCard card(ResultSet row) throws SQLException {
        var document = readDocument(row.getString("result_json"));
        var publishedAt = row.getObject("published_at", OffsetDateTime.class);
        var lastViewedAt = row.getObject("last_viewed_at", OffsetDateTime.class);
        var video = new VideoInfo(row.getString("video_id"), row.getString("title"), row.getString("channel_name"),
                row.getString("channel_image_url"), row.getString("thumbnail_url"),
                publishedAt == null ? null : publishedAt.toInstant(), row.getObject("duration_ms", Long.class));
        return new AnalysisCard(row.getObject("id", UUID.class), row.getObject("analysis_id", UUID.class), video,
                document.summary().oneLine(), document.summary().keywords(),
                row.getObject("analyzed_at", OffsetDateTime.class).toInstant(),
                lastViewedAt == null ? null : lastViewedAt.toInstant());
    }

    private ContinueReading continueReading(ResultSet row) throws SQLException {
        var document = readDocument(row.getString("result_json"));
        var sectionId = row.getObject("reading_section_id", UUID.class);
        var section = document.sections().stream().filter(s -> s.sectionId().equals(sectionId.toString()))
                .findFirst().orElseThrow(() -> new BusinessException(INTERNAL_ERROR));
        return new ContinueReading(row.getObject("id", UUID.class), row.getObject("analysis_id", UUID.class),
                row.getString("title"), row.getString("reading_tab"), sectionId, section.title(),
                section.startMs(), section.endMs(), null, row.getObject("last_viewed_at", OffsetDateTime.class).toInstant(),
                section.summary(), section.detail().applicationTips().size());
    }

    private ResultDocument readDocument(String json) {
        try {
            var document = mapper.readValue(json, ResultDocument.class);
            ResultDocumentValidator.validate(document);
            return document;
        } catch (RuntimeException exception) {
            throw new BusinessException(INTERNAL_ERROR);
        }
    }
}
