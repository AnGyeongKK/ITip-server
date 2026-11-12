package com.gkk.itip.api.archive.service;

import com.gkk.itip.api.archive.dto.response.PlaylistGroupResponse;
import com.gkk.itip.api.archive.dto.response.PlaylistGroupResponse.Page;
import com.gkk.itip.api.archive.repository.PlaylistGroupRepository;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.request.RequestFingerprint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.INVALID_CURSOR;
import static com.gkk.itip.common.exception.ErrorCode.INVALID_REQUEST;
import static com.gkk.itip.common.exception.ErrorCode.RESOURCE_NOT_FOUND;

@Service
@Transactional(readOnly = true)
public class PlaylistGroupQuery {
    private final PlaylistGroupRepository groups;
    private final JsonMapper mapper;

    public PlaylistGroupQuery(PlaylistGroupRepository groups, JsonMapper mapper) {
        this.groups = groups;
        this.mapper = mapper;
    }

    public Page list(String ownerId, String cursor, int limit) {
        if (limit < 1 || limit > 50) throw new BusinessException(INVALID_REQUEST);
        String scope = RequestFingerprint.of(mapper, List.of(ownerId, "playlist-groups"));
        var before = decode(cursor, scope);
        var groupsPage = groups.list(ownerId, before.time(), before.id(), limit + 1);
        if (groupsPage.size() <= limit) return new Page(groupsPage, null, false);
        var items = List.copyOf(groupsPage.subList(0, limit));
        var last = items.getLast();
        String value = last.lastAnalyzedAt() + "|" + last.groupId() + "|" + scope;
        String next = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return new Page(items, next, true);
    }

    public PlaylistGroupResponse get(String ownerId, UUID groupId) {
        return groups.find(ownerId, groupId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
    }

    private static Cursor decode(String cursor, String scope) {
        if (cursor == null) return new Cursor(null, null);
        if (cursor.length() > 200) throw new BusinessException(INVALID_CURSOR);
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !scope.equals(parts[2])) throw new BusinessException(INVALID_CURSOR);
            UUID id = UUID.fromString(parts[1]);
            if (!id.toString().equals(parts[1])) throw new BusinessException(INVALID_CURSOR);
            return new Cursor(Instant.parse(parts[0]).atOffset(ZoneOffset.UTC), id);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(INVALID_CURSOR);
        }
    }

    private record Cursor(OffsetDateTime time, UUID id) {}
}
