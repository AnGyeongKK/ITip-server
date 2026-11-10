package com.gkk.itip.api.archive.repository;

import com.gkk.itip.api.archive.dto.RecordDetailResponse.FolderInfo;
import com.gkk.itip.api.archive.dto.response.FolderResponse.Created;
import com.gkk.itip.api.archive.dto.response.FolderResponse.Item;
import com.gkk.itip.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.RESOURCE_NOT_FOUND;

@Repository
public class FolderRepository {
    private static final String FOLDERS = """
            select f.id, f.name, f.created_at,
                   (select count(*) from folder_records fr where fr.folder_id = f.id) as record_count
            from folders f
            """;
    private static final RowMapper<Item> FOLDER = (row, index) -> new Item(row.getObject("id", UUID.class),
            row.getString("name"), row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getLong("record_count"));

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public FolderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public List<Item> list(String ownerId, Instant before, UUID beforeId, int limit) {
        var timestamp = before == null ? null : before.atOffset(ZoneOffset.UTC);
        return jdbc.query(FOLDERS + """
                where f.owner_id = ? and (?::timestamptz is null or (f.created_at, f.id) < (?, ?))
                order by f.created_at desc, f.id desc
                limit ?
                """, FOLDER, ownerId, timestamp, timestamp, beforeId, limit);
    }

    public Optional<Item> find(String ownerId, UUID folderId) {
        return jdbc.query(FOLDERS + "where f.id = ? and f.owner_id = ?", FOLDER, folderId, ownerId).stream().findFirst();
    }

    public void requireOwned(String ownerId, UUID folderId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from folders where id = ? and owner_id = ?)",
                Boolean.class, folderId, ownerId))) {
            throw new BusinessException(RESOURCE_NOT_FOUND);
        }
    }

    public void create(String ownerId, Created folder) {
        jdbc.update("insert into folders (id, owner_id, name, created_at) values (?, ?, ?, ?)",
                folder.folderId(), ownerId, folder.name(), folder.createdAt().atOffset(ZoneOffset.UTC));
    }

    public int rename(String ownerId, UUID folderId, String name) {
        return jdbc.update("update folders set name = ? where id = ? and owner_id = ?", name, folderId, ownerId);
    }

    public int delete(String ownerId, UUID folderId) {
        return jdbc.update("delete from folders where id = ? and owner_id = ?", folderId, ownerId);
    }

    public List<UUID> addRecords(String ownerId, UUID folderId, Collection<UUID> recordIds) {
        return namedJdbc.query("""
                insert into folder_records (folder_id, record_id, owner_id)
                select :folderId, id, owner_id from analysis_records
                where owner_id = :ownerId and id in (:recordIds)
                on conflict (folder_id, record_id) do nothing
                returning record_id
                """, Map.of("ownerId", ownerId, "folderId", folderId, "recordIds", recordIds),
                (row, index) -> row.getObject("record_id", UUID.class));
    }

    public void removeRecord(String ownerId, UUID folderId, UUID recordId) {
        jdbc.update("delete from folder_records where folder_id = ? and record_id = ? and owner_id = ?",
                folderId, recordId, ownerId);
    }

    public List<FolderInfo> memberships(String ownerId, UUID recordId) {
        return jdbc.query("""
                select f.id, f.name
                from folder_records fr
                join folders f on f.id = fr.folder_id and f.owner_id = fr.owner_id
                where fr.record_id = ? and fr.owner_id = ?
                order by f.created_at desc, f.id desc
                """, (row, index) -> new FolderInfo(row.getObject("id", UUID.class), row.getString("name")), recordId, ownerId);
    }
}
