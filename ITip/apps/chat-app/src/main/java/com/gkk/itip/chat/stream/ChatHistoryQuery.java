package com.gkk.itip.chat.stream;

import com.gkk.itip.chat.conversation.service.ChatScopeQuery.RecordRef;
import com.gkk.itip.domain.chat.MessageRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ChatHistoryQuery {
    private final NamedParameterJdbcTemplate jdbc;
    public ChatHistoryQuery(JdbcTemplate jdbc) { this.jdbc=new NamedParameterJdbcTemplate(jdbc); }
    private record Pair(String question,String answer) {}

    public List<ChatHistoryEntry> select(String owner,UUID room,List<RecordRef> allowed) {
        if(allowed.isEmpty()) return List.of();
        var pairs=jdbc.query("""
            select q.content as question,a.content as answer
            from chat_messages a join chat_messages q on q.id=a.reply_to_message_id and q.conversation_id=a.conversation_id
            where a.conversation_id=:room and a.role='ASSISTANT' and q.role='USER' and a.status='COMPLETED'
              and (a.finish_reason='INSUFFICIENT_EVIDENCE' or exists(select 1 from chat_message_sources s where s.message_id=a.id))
              and not exists (
                select 1 from chat_message_sources s
                left join analysis_records r on r.id=s.record_id and r.analysis_id=s.analysis_id and r.owner_id=:owner
                where s.message_id=a.id and (r.id is null or r.id not in (:records))
              )
            order by a.position desc limit 5
            """,Map.of("room",room,"owner",owner,"records",allowed.stream().map(RecordRef::recordId).toList()),
            (r,n)->new Pair(r.getString("question"),r.getString("answer")));
        var selected=new ArrayList<Pair>(); int chars=0;
        for(var pair:pairs) {
            int size=pair.question().length()+pair.answer().length();
            if(size>ChatLimits.MAX_HISTORY_CHARS-chars) continue;
            selected.add(pair); chars+=size;
        }
        var result=new ArrayList<ChatHistoryEntry>();
        for(var pair:selected.reversed()) {
            result.add(new ChatHistoryEntry(MessageRole.USER,pair.question()));
            result.add(new ChatHistoryEntry(MessageRole.ASSISTANT,pair.answer()));
        }
        return List.copyOf(result);
    }
}
