package com.gkk.itip.api.archive.service;

import com.gkk.itip.api.archive.dto.response.FolderResponse;
import com.gkk.itip.api.archive.dto.response.FolderResponse.Created;
import com.gkk.itip.api.archive.dto.response.FolderResponse.Page;
import com.gkk.itip.api.archive.dto.response.FolderResponse.RecordsAdded;
import com.gkk.itip.api.archive.repository.FolderRepository;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.request.RequestFingerprint;
import com.gkk.itip.domain.analysis.AnalysisOwnerLock;
import com.gkk.itip.domain.analysis.AnalysisRecordRepository;
import com.gkk.itip.domain.analysis.IdempotencyRequest;
import com.gkk.itip.domain.analysis.IdempotencyRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.IDEMPOTENCY_CONFLICT;
import static com.gkk.itip.common.exception.ErrorCode.FOLDER_NAME_DUPLICATED;
import static com.gkk.itip.common.exception.ErrorCode.INVALID_CURSOR;
import static com.gkk.itip.common.exception.ErrorCode.INVALID_REQUEST;
import static com.gkk.itip.common.exception.ErrorCode.RESOURCE_NOT_FOUND;

@Service
@Transactional(readOnly = true)
public class FolderService {
    private static final String CREATE_FOLDER = "CREATE_FOLDER";

    private final FolderRepository folders;
    private final AnalysisRecordRepository records;
    private final IdempotencyRequestRepository requests;
    private final AnalysisOwnerLock ownerLock;
    private final JsonMapper mapper;
    private final Clock clock;

    public FolderService(FolderRepository folders, AnalysisRecordRepository records, IdempotencyRequestRepository requests,
                         AnalysisOwnerLock ownerLock, JsonMapper mapper, Clock clock) {
        this.folders = folders;
        this.records = records;
        this.requests = requests;
        this.ownerLock = ownerLock;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Page list(String ownerId, String cursor, int limit) {
        if (limit < 1 || limit > 50) throw new BusinessException(INVALID_REQUEST);
        String scope = RequestFingerprint.of(mapper, ownerId);
        var before = decodeCursor(cursor, scope);
        var items = folders.list(ownerId, before.createdAt(), before.id(), limit + 1);
        boolean hasNext = items.size() > limit;
        if (!hasNext) return new Page(items, null, false);
        var page = List.copyOf(items.subList(0, limit));
        var last = page.getLast();
        String value = last.createdAt() + "|" + last.folderId() + "|" + scope;
        String next = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return new Page(page, next, true);
    }

    @Transactional
    public Created create(String ownerId, UUID key, String name) {
        ownerLock.lock(ownerId);
        String normalized = normalizeName(name);
        String hash = RequestFingerprint.of(mapper, normalized);
        var replay = requests.findByOwnerIdAndOperationAndKey(ownerId, CREATE_FOLDER, key);
        if (replay.isPresent()) {
            if (!replay.get().matches(hash)) throw new BusinessException(IDEMPOTENCY_CONFLICT);
            var existing = folders.find(ownerId, replay.get().getTargetId())
                    .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
            return new Created(existing.folderId(), existing.name(), existing.createdAt());
        }
        if (folders.nameExists(ownerId, normalized, null)) throw new BusinessException(FOLDER_NAME_DUPLICATED);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var folder = new Created(UUID.randomUUID(), normalized, now);
        folders.create(ownerId, folder);
        requests.save(new IdempotencyRequest(ownerId, CREATE_FOLDER, key, hash, folder.folderId(), now));
        return folder;
    }

    public FolderResponse get(String ownerId, UUID folderId) {
        var folder = folders.find(ownerId, folderId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        return new FolderResponse(folderId, folder.name(), folder.recordCount());
    }

    @Transactional
    public FolderResponse rename(String ownerId, UUID folderId, String name) {
        ownerLock.lock(ownerId);
        folders.requireOwned(ownerId, folderId);
        String normalized = normalizeName(name);
        if (folders.nameExists(ownerId, normalized, folderId)) throw new BusinessException(FOLDER_NAME_DUPLICATED);
        if (folders.rename(ownerId, folderId, normalized) == 0) throw new BusinessException(RESOURCE_NOT_FOUND);
        return get(ownerId, folderId);
    }

    @Transactional
    public void delete(String ownerId, UUID folderId) {
        ownerLock.lock(ownerId);
        if (folders.delete(ownerId, folderId) == 0) throw new BusinessException(RESOURCE_NOT_FOUND);
    }

    @Transactional
    public RecordsAdded addRecords(String ownerId, UUID folderId, List<UUID> recordIds) {
        ownerLock.lock(ownerId);
        folders.requireOwned(ownerId, folderId);
        var distinct = new LinkedHashSet<>(recordIds);
        if (records.findAllByIdInAndOwnerId(distinct, ownerId).size() != distinct.size()) {
            throw new BusinessException(RESOURCE_NOT_FOUND);
        }
        var added = new HashSet<>(folders.addRecords(ownerId, folderId, distinct));
        return new RecordsAdded(folderId, distinct.stream().filter(added::contains).toList(),
                distinct.stream().filter(id -> !added.contains(id)).toList(), get(ownerId, folderId).recordCount());
    }

    @Transactional
    public void removeRecord(String ownerId, UUID folderId, UUID recordId) {
        ownerLock.lock(ownerId);
        folders.requireOwned(ownerId, folderId);
        records.findByIdAndOwnerId(recordId, ownerId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        folders.removeRecord(ownerId, folderId, recordId);
    }

    private static String normalizeName(String name) {
        if (name == null) throw new BusinessException(INVALID_REQUEST);
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 20)
            throw new BusinessException(INVALID_REQUEST);
        return normalized;
    }

    private static Cursor decodeCursor(String value, String scope) {
        if (value == null) return new Cursor(null, null);
        if (value.length() > 200) throw new BusinessException(INVALID_CURSOR);
        try {
            var parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !scope.equals(parts[2])) throw new BusinessException(INVALID_CURSOR);
            var id = UUID.fromString(parts[1]);
            if (!id.toString().equals(parts[1])) throw new BusinessException(INVALID_CURSOR);
            return new Cursor(Instant.parse(parts[0]), id);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(INVALID_CURSOR);
        }
    }

    private record Cursor(Instant createdAt, UUID id) {}
}
