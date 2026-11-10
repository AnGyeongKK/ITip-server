package com.gkk.itip.api.search.service;

import com.gkk.itip.api.archive.repository.FolderRepository;
import com.gkk.itip.api.archive.service.PlaylistGroupQuery;
import com.gkk.itip.api.search.dto.response.RecordKeywordsResponse;
import com.gkk.itip.api.search.dto.response.RecordKeywordsResponse.KeywordCount;
import com.gkk.itip.api.search.dto.response.RecordSearchResponse;
import com.gkk.itip.api.search.dto.response.RecordSearchResponse.Filters;
import com.gkk.itip.api.search.dto.response.RecordSearchResponse.FolderMembership;
import com.gkk.itip.api.search.dto.response.RecordSearchResponse.Item;
import com.gkk.itip.api.search.dto.response.RecordSearchResponse.Match;
import com.gkk.itip.api.search.dto.response.RecordSearchResponse.Match.Field;
import com.gkk.itip.api.search.dto.response.RecordSearchResponse.SelectionContext;
import com.gkk.itip.api.search.repository.RecordSearchRepository;
import com.gkk.itip.api.search.repository.RecordSearchRepository.StoredRecord;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.request.RequestFingerprint;
import com.gkk.itip.domain.analysis.result.ResultDocument;
import com.gkk.itip.domain.analysis.result.ResultDocument.Keyword;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.gkk.itip.common.exception.ErrorCode.INVALID_CURSOR;
import static com.gkk.itip.common.exception.ErrorCode.INVALID_REQUEST;
import static com.gkk.itip.common.exception.ErrorCode.RESOURCE_NOT_FOUND;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RecordSearchService {
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_KEYWORD_IDS = 50;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_MATCHES = 12;
    private static final int MATCH_TEXT_LENGTH = 160;

    private final RecordSearchRepository repository;
    private final FolderRepository folders;
    private final PlaylistGroupQuery playlistGroups;
    private final JsonMapper mapper;

    public RecordSearchService(RecordSearchRepository repository, FolderRepository folders,
                               PlaylistGroupQuery playlistGroups, JsonMapper mapper) {
        this.repository = repository;
        this.folders = folders;
        this.playlistGroups = playlistGroups;
        this.mapper = mapper;
    }

    public RecordSearchResponse records(String ownerId, String q, String folderId, String playlistGroupId,
                                        String targetFolderId, List<String> keywordIds, String cursor, int limit) {
        UUID folder = ownedFolder(ownerId, folderId);
        UUID target = ownedFolder(ownerId, targetFolderId);
        UUID groupId = scopeId(playlistGroupId);
        String playlist = groupId == null ? null : playlistGroups.get(ownerId, groupId).playlistId();
        var filters = filters(q, folder, groupId, keywordIds);
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new BusinessException(INVALID_REQUEST);
        String fingerprint = RequestFingerprint.of(mapper,
                target == null ? List.of(ownerId, filters) : List.of(ownerId, filters, target));
        var boundary = decodeCursor(cursor, fingerprint);
        List<Item> page = new ArrayList<>(limit + 1);
        long matchedCount = scan(ownerId, folder, target, playlist, filters, null, found -> {
            var record = found.record();
            if (page.size() <= limit && (boundary == null || boundary.isBefore(record))) {
                var membership = target == null ? null : new FolderMembership(target.toString(), record.included());
                page.add(new Item(record.recordId(), record.analysisId(), record.video(), found.oneLine(),
                        List.copyOf(found.keywords().values()), record.analyzedAt(), found.matches(), membership));
            }
        });
        boolean hasNext = page.size() > limit;
        if (hasNext) page.removeLast();
        String nextCursor = hasNext ? encodeCursor(page.getLast(), fingerprint) : null;
        return new RecordSearchResponse(List.copyOf(page), nextCursor, hasNext, matchedCount, filters,
                new SelectionContext(target == null ? null : target.toString()));
    }

    public RecordKeywordsResponse keywords(String ownerId, String q, String folderId, String playlistGroupId,
                                           List<String> keywordIds) {
        UUID folder = ownedFolder(ownerId, folderId);
        UUID groupId = scopeId(playlistGroupId);
        String playlist = groupId == null ? null : playlistGroups.get(ownerId, groupId).playlistId();
        var filters = filters(q, folder, groupId, keywordIds);
        Map<String, String> selectedNames = new LinkedHashMap<>();
        filters.keywordIds().forEach(id -> selectedNames.put(id, null));
        Map<String, KeywordCount> counts = new LinkedHashMap<>();
        long matchedCount = scan(ownerId, folder, null, playlist, filters, selectedNames, found ->
                found.keywords().forEach((id, keyword) -> counts.compute(id, (key, count) ->
                        new KeywordCount(id, count == null ? keyword.name() : count.name(),
                                count == null ? 1 : count.recordCount() + 1, selectedNames.containsKey(id)))));
        selectedNames.forEach((id, name) -> counts.putIfAbsent(id, new KeywordCount(id, name == null ? id : name, 0, true)));
        var keywords = counts.values().stream()
                .sorted(Comparator.comparingLong(KeywordCount::recordCount).reversed()
                        .thenComparing(KeywordCount::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(KeywordCount::keywordId))
                .toList();
        return new RecordKeywordsResponse(keywords, matchedCount);
    }

    private long scan(String ownerId, UUID folderId, UUID targetFolderId, String playlistId, Filters filters,
                      Map<String, String> selectedNames, Consumer<MatchingRecord> accept) {
        Pattern query = filters.q() == null ? null
                : Pattern.compile(Pattern.quote(filters.q()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        // Both responses need the exact count. Stream the owner's completed records within the requested scope.
        long[] matchedCount = {0};
        repository.scanCompleted(ownerId, folderId, targetFolderId, playlistId, record -> {
            var document = mapper.readValue(record.resultJson(), ResultDocument.class);
            var keywords = collectKeywords(document);
            if (selectedNames != null) {
                selectedNames.replaceAll((id, name) -> name == null && keywords.containsKey(id) ? keywords.get(id).name() : name);
            }
            if (!keywords.keySet().containsAll(filters.keywordIds())) return;
            var matches = query == null ? List.<Match>of() : findMatches(record, document, query);
            if (query != null && matches.isEmpty()) return;
            matchedCount[0]++;
            accept.accept(new MatchingRecord(record, document.summary().oneLine(), keywords, matches));
        });
        return matchedCount[0];
    }

    private static Map<String, Keyword> collectKeywords(ResultDocument document) {
        Map<String, Keyword> keywords = new LinkedHashMap<>();
        document.summary().keywords().forEach(keyword -> keywords.putIfAbsent(keyword.keywordId(), keyword));
        return keywords;
    }

    private static List<Match> findMatches(StoredRecord record, ResultDocument document, Pattern query) {
        var matches = new LinkedHashSet<Match>();
        var summary = document.summary();
        addMatch(matches, query, Field.TITLE, record.video().title(), null);
        addMatch(matches, query, Field.ONE_LINE, summary.oneLine(), null);
        summary.keywords().forEach(keyword -> addMatch(matches, query, Field.KEYWORD, keyword.name(), null));
        summary.mentionedTools().forEach(tool -> addMatch(matches, query, Field.TOOL, tool.name(), null));
        for (var section : document.sections()) {
            if (matches.size() >= MAX_MATCHES) break;
            var id = section.sectionId();
            var detail = section.detail();
            addMatch(matches, query, Field.DETAIL, section.title(), id);
            addMatch(matches, query, Field.DETAIL, section.summary(), id);
            section.subtopics().forEach(text -> addMatch(matches, query, Field.DETAIL, text, id));
            section.keywords().forEach(keyword -> addMatch(matches, query, Field.KEYWORD, keyword.name(), id));
            addMatch(matches, query, Field.DETAIL, detail.takeaway(), id);
            addMatch(matches, query, Field.DETAIL, detail.explanation(), id);
            detail.applicationTips().forEach(point -> addMatch(matches, query, Field.DETAIL, point.text(), id));
            detail.examples().forEach(point -> addMatch(matches, query, Field.DETAIL, point.text(), id));
            detail.usageKeywords().forEach(text -> addMatch(matches, query, Field.TECHNOLOGY, text, id));
            detail.recommendedTools().forEach(tool -> {
                addMatch(matches, query, Field.TOOL, tool.name(), id);
                addMatch(matches, query, Field.DETAIL, tool.reason(), id);
            });
        }
        return List.copyOf(matches);
    }

    private static void addMatch(LinkedHashSet<Match> matches, Pattern query, Field field, String value, String sectionId) {
        if (value == null || matches.size() >= MAX_MATCHES) return;
        String text = normalize(value);
        var match = query.matcher(text);
        if (!match.find()) return;
        int start = Math.max(0, match.start() - 32);
        int end = Math.min(text.length(), Math.max(start + MATCH_TEXT_LENGTH, match.end()));
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) start--;
        if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end++;
        matches.add(new Match(field, text.substring(start, end).strip(), sectionId));
    }

    private static Filters filters(String q, UUID folderId, UUID playlistGroupId, List<String> keywordIds) {
        String query = q == null ? "" : normalize(q);
        if (query.length() > MAX_QUERY_LENGTH) throw new BusinessException(INVALID_REQUEST);
        var ids = new TreeSet<String>();
        if (keywordIds != null) {
            if (keywordIds.size() > MAX_KEYWORD_IDS) throw new BusinessException(INVALID_REQUEST);
            for (String id : keywordIds) {
                if (id == null || id.isBlank() || id.length() > 64) throw new BusinessException(INVALID_REQUEST);
                ids.add(id.strip());
            }
        }
        return new Filters(query.isEmpty() ? null : query, folderId == null ? null : folderId.toString(),
                List.copyOf(ids), playlistGroupId == null ? null : playlistGroupId.toString());
    }

    private UUID ownedFolder(String ownerId, String id) {
        UUID folderId = scopeId(id);
        if (folderId != null) folders.requireOwned(ownerId, folderId);
        return folderId;
    }

    private static UUID scopeId(String id) {
        if (id == null) return null;
        if (id.isBlank()) throw new BusinessException(INVALID_REQUEST);
        UUID scope;
        try {
            scope = UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(RESOURCE_NOT_FOUND);
        }
        if (!scope.toString().equalsIgnoreCase(id)) throw new BusinessException(RESOURCE_NOT_FOUND);
        return scope;
    }

    private static String normalize(String text) {
        return text.strip();
    }

    private static String encodeCursor(Item item, String fingerprint) {
        String value = item.analyzedAt() + "|" + item.recordId() + "|" + fingerprint;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor, String fingerprint) {
        if (cursor == null) return null;
        if (cursor.isEmpty() || cursor.length() > 200) throw new BusinessException(INVALID_CURSOR);
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !fingerprint.equals(parts[2])) throw new BusinessException(INVALID_CURSOR);
            var id = UUID.fromString(parts[1]);
            if (!id.toString().equals(parts[1])) throw new BusinessException(INVALID_CURSOR);
            return new Cursor(Instant.parse(parts[0]), id);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new BusinessException(INVALID_CURSOR);
        }
    }

    private record Cursor(Instant analyzedAt, UUID recordId) {
        boolean isBefore(StoredRecord candidate) {
            int timeOrder = candidate.analyzedAt().compareTo(analyzedAt);
            return timeOrder < 0 || (timeOrder == 0 && candidate.recordId().toString().compareTo(recordId.toString()) < 0);
        }
    }

    private record MatchingRecord(StoredRecord record, String oneLine, Map<String, Keyword> keywords, List<Match> matches) {}
}
