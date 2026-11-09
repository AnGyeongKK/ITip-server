package com.gkk.itip.chat.retrieval;

import com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ChatContextQuery {
    private static final int MAX_CONTEXT_CHARS = 24000;
    private final NamedParameterJdbcTemplate jdbc;
    private final int topK;
    public ChatContextQuery(JdbcTemplate jdbc, @Value("${itip.chat.retrieval-top-k:8}") int topK) {
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("retrieval topK must be 1..20");
        var bounded = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        bounded.setQueryTimeout(3);
        this.jdbc = new NamedParameterJdbcTemplate(bounded); this.topK = topK;
    }
    public List<ChatContext> search(String owner, List<RecordRef> allowed, QuestionEmbeddingClient.Vector query) {
        if (allowed.isEmpty()) return List.of();
        var args = Map.of("owner", owner, "records", allowed.stream().map(RecordRef::recordId).toList(),
            "model", query.model(), "dimensions", query.values().length, "vector", Arrays.toString(query.values()), "limit", topK);
        // MATERIALIZED는 혼재된 모델·차원의 벡터에 거리 연산을 먼저 적용하는 실행 계획을 막는다.
        var rows = jdbc.query("""
            with eligible as materialized (
              select r.id as record_id,r.analysis_id,r.video_id,v.title,c.id as chunk_id,c.text,c.start_ms,c.end_ms,c.embedding,
                array(select s.id from analysis_sections s where s.analysis_id=c.analysis_id and s.id=any(c.section_ids)
                  and c.start_ms<s.end_ms and c.end_ms>s.start_ms order by s.id) as section_ids
              from analysis_records r
              join analysis_results a on a.id=r.analysis_id and a.owner_id=r.owner_id and a.video_id=r.video_id
              join analysis_chunks c on c.analysis_id=r.analysis_id and c.owner_id=r.owner_id
              join videos v on v.video_id=r.video_id
              where r.owner_id=:owner and r.id in (:records) and a.chat_ready=true and a.completed_at is not null
                and c.source_type='CAPTION' and c.embedding_model=:model and c.embedding_dimensions=:dimensions
            )
            select * from eligible where cardinality(section_ids)>0
            order by embedding <=> cast(:vector as vector),record_id,chunk_id limit :limit
            """, args, (r,n) -> new ChatContext(r.getObject("record_id",UUID.class),r.getObject("analysis_id",UUID.class),
                r.getObject("chunk_id",UUID.class), Arrays.asList((UUID[])r.getArray("section_ids").getArray()),
                r.getString("video_id"),r.getString("title"),r.getString("text"),r.getLong("start_ms"),r.getLong("end_ms")));
        var result = new ArrayList<ChatContext>(); int remaining = MAX_CONTEXT_CHARS;
        for (var row : rows) {
            if (!allowed.contains(new RecordRef(row.recordId(), row.analysisId()))) continue;
            if (row.text().length() > remaining) continue;
            result.add(row); remaining -= row.text().length();
        }
        return List.copyOf(result);
    }
}
