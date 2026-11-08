package com.gkk.itip.chat.conversation.service;

import com.gkk.itip.chat.conversation.dto.ChatScope;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.analysis.result.ResultDocument;
import com.gkk.itip.domain.analysis.result.ResultDocumentValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import static com.gkk.itip.common.exception.ErrorCode.INTERNAL_ERROR;

@Component
public class ChatScopeQuery {
    public record RecordRef(UUID recordId, UUID analysisId) {}
    public record Selection(String status, String label, List<RecordRef> records) {}
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    public ChatScopeQuery(JdbcTemplate jdbc, JsonMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public ChatScope readScope(String json) {
        try {
            var scope = mapper.readValue(json, ChatScope.class);
            if (scope == null || scope.type() == null) throw new IllegalArgumentException("missing stored scope");
            return scope;
        } catch (RuntimeException exception) { throw new BusinessException(INTERNAL_ERROR); }
    }

    public Selection resolve(String owner, ChatScope scope) {
        String folder = null;
        if (scope.folderId() != null) {
            var names = jdbc.queryForList("select name from folders where id=? and owner_id=?", String.class, scope.folderId(), owner);
            if (names.isEmpty()) return new Selection("UNAVAILABLE", "사용할 수 없는 폴더", List.of());
            folder = names.getFirst();
        }
        String sql = """
                select r.id,r.analysis_id,a.result_json,v.title from analysis_records r
                join analysis_results a on a.id=r.analysis_id and a.owner_id=r.owner_id and a.video_id=r.video_id
                join videos v on v.video_id=r.video_id
                where r.owner_id=? and a.chat_ready=true and a.completed_at is not null
                """;
        List<Object> args = new ArrayList<>(List.of(owner));
        if (scope.folderId() != null) {
            sql += " and exists (select 1 from folder_records f where f.folder_id=? and f.record_id=r.id and f.owner_id=r.owner_id)";
            args.add(scope.folderId());
        }
        sql += " order by r.analyzed_at desc,r.id desc";
        Map<UUID, String> labels = new HashMap<>();
        var matches = jdbc.query(sql, (rs, row) -> {
            ResultDocument document;
            try {
                document = mapper.readValue(rs.getString("result_json"), ResultDocument.class);
                ResultDocumentValidator.validate(document);
            } catch (RuntimeException exception) { throw new BusinessException(INTERNAL_ERROR); }
            var keywords = document.summary().keywords();
            var ids = new HashSet<UUID>();
            keywords.forEach(k -> { var id = UUID.fromString(k.keywordId()); ids.add(id); labels.putIfAbsent(id, k.name()); });
            boolean selected = ids.containsAll(scope.keywordIds());
            if (selected && scope.q() != null) selected = searchable(rs.getString("title"), document).stream()
                    .anyMatch(text -> text.toLowerCase(Locale.ROOT).contains(scope.q().toLowerCase(Locale.ROOT)));
            return selected ? new RecordRef(rs.getObject("id", UUID.class), rs.getObject("analysis_id", UUID.class)) : null;
        }, args.toArray()).stream().filter(Objects::nonNull).toList();
        List<String> parts = new ArrayList<>();
        if (folder != null) parts.add(folder);
        if (!scope.keywordIds().isEmpty()) parts.add(String.join(" + ", scope.keywordIds().stream().map(id -> labels.getOrDefault(id, "선택 키워드")).toList()));
        if (scope.q() != null) parts.add("검색: " + scope.q());
        String label = parts.isEmpty() ? "전체 기록" : String.join(" · ", parts);
        if (label.codePointCount(0, label.length()) > 500) label = label.substring(0, label.offsetByCodePoints(0, 497)) + "...";
        return new Selection(matches.isEmpty() ? "EMPTY" : "AVAILABLE", label, matches);
    }

    private static List<String> searchable(String title, ResultDocument doc) {
        var texts = new ArrayList<String>();
        if (title != null) texts.add(title);
        texts.add(doc.summary().oneLine());
        doc.summary().keywords().forEach(k -> texts.add(k.name()));
        doc.summary().mentionedTools().forEach(t -> texts.add(t.name()));
        for (var s : doc.sections()) {
            texts.add(s.title()); texts.add(s.summary()); texts.addAll(s.subtopics());
            s.keywords().forEach(k -> texts.add(k.name()));
            var d = s.detail(); texts.add(d.takeaway()); texts.add(d.explanation()); texts.addAll(d.usageKeywords());
            d.applicationTips().forEach(t -> texts.add(t.text())); d.examples().forEach(t -> texts.add(t.text()));
            d.recommendedTools().forEach(t -> { texts.add(t.name()); texts.add(t.reason()); });
        }
        return texts;
    }
}
