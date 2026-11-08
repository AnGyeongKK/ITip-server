package com.gkk.itip.chat.stream;

import com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef;
import com.gkk.itip.domain.chat.ChatMessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
public class ChatCitationQuery {
    private final NamedParameterJdbcTemplate jdbc;
    public ChatCitationQuery(JdbcTemplate jdbc) { this.jdbc=new NamedParameterJdbcTemplate(jdbc); }
    private record Evidence(ChatCitation citation, UUID analysis, String video, String title, long start, long end, Instant analyzedAt) {}

    public List<ChatMessageSource> validate(GenerationTicket ticket, List<ChatCitation> input, List<RecordRef> current) {
        if (input.size()>ChatLimits.MAX_SOURCES) throw new ChatProtocolException();
        var citations=new LinkedHashSet<>(input);
        if (citations.isEmpty()) return List.of();
        var allowed=new HashSet<>(ticket.records()); allowed.retainAll(current);
        var rows=jdbc.query("""
            select r.id,r.analysis_id,r.video_id,r.analyzed_at,v.title,c.id as chunk_id,s.id as section_id,
              greatest(c.start_ms,s.start_ms) as start_ms,least(c.end_ms,s.end_ms) as end_ms
            from analysis_records r
            join analysis_results a on a.id=r.analysis_id and a.owner_id=r.owner_id and a.video_id=r.video_id
            join videos v on v.video_id=r.video_id
            join analysis_chunks c on c.analysis_id=r.analysis_id and c.owner_id=r.owner_id
            join analysis_sections s on s.analysis_id=r.analysis_id and s.id=any(c.section_ids)
            where r.owner_id=:owner and r.id in (:records) and c.id in (:chunks) and s.id in (:sections)
              and a.chat_ready=true and a.completed_at is not null
              and c.start_ms<s.end_ms and c.end_ms>s.start_ms
            """,Map.of("owner",ticket.ownerId(),"records",citations.stream().map(ChatCitation::recordId).toList(),
                "chunks",citations.stream().map(ChatCitation::chunkId).toList(),"sections",citations.stream().map(ChatCitation::sectionId).toList()),
            (r,n)->new Evidence(new ChatCitation(r.getObject("id",UUID.class),r.getObject("chunk_id",UUID.class),r.getObject("section_id",UUID.class)),
                r.getObject("analysis_id",UUID.class),r.getString("video_id"),r.getString("title"),r.getLong("start_ms"),r.getLong("end_ms"),r.getTimestamp("analyzed_at").toInstant()));
        var byCitation=new HashMap<ChatCitation,Evidence>(); for(var row:rows) byCitation.put(row.citation(),row);
        var result=new ArrayList<ChatMessageSource>();
        for(var citation:citations) {
            var e=byCitation.get(citation);
            if(e==null || !allowed.contains(new RecordRef(citation.recordId(),e.analysis()))) throw new ChatProtocolException();
            result.add(ChatMessageSource.snapshot(ticket.assistantMessageId(),"src-"+(result.size()+1),citation.recordId(),e.analysis(),
                citation.sectionId(),e.video(),e.title(),e.start(),e.end(),e.analyzedAt()));
        }
        return result;
    }
}
