package com.gkk.itip.chat;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

final class ChatTestData {
    static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    final JdbcTemplate jdbc;
    ChatTestData(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    void clear() { jdbc.execute("truncate chat_conversations, folders, videos, analysis_batches, idempotency_requests cascade"); }

    UUID room(String owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into chat_conversations(id,owner_id,scope_json,scope_label,title,created_at,updated_at)
                values (?,?,'{"type":"ALL","folderId":null,"keywordIds":[],"q":null}','전체 기록','새 대화',?,?)
                """, id, owner, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }
    UUID question(UUID room, UUID request, int position, String content) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into chat_messages(id,conversation_id,position,role,client_request_id,request_hash,content,created_at)
                values (?,?,?,'USER',?,'fixture',?,?)
                """, id, room, position, request, content, Timestamp.from(NOW));
        jdbc.update("update chat_conversations set next_position=greatest(next_position,?) where id=?", position, room);
        return id;
    }
    UUID answer(UUID room, UUID request, UUID question, int position, String state, String content, int scopeCount) {
        UUID id = UUID.randomUUID();
        boolean completed = state.equals("COMPLETED"), active = state.equals("GENERATING");
        jdbc.update("""
                insert into chat_messages(id,conversation_id,position,role,client_request_id,request_hash,content,created_at,
                reply_to_message_id,status,scope_record_count,used_record_count,retryable,finish_reason,completed_at,
                generation_token,lease_until,error_code,error_message)
                values (?,?,?,'ASSISTANT',?,'fixture',?,?,?,?,?,0,?,?,?,?,?,?,?)
                """, id, room, position, request, content, Timestamp.from(NOW), question, state, scopeCount,
                !completed && !active, completed ? "STOP" : null, completed ? Timestamp.from(NOW.plusSeconds(1)) : null,
                active ? UUID.randomUUID() : null, active ? Timestamp.from(NOW.plusSeconds(60)) : null,
                !completed && !active ? "CHAT_STREAM_FAILED" : null, !completed && !active ? "답변 생성이 중단됐어요." : null);
        jdbc.update("update chat_conversations set next_position=greatest(next_position,?) where id=?", position, room);
        return id;
    }
    record Analysis(UUID recordId, UUID analysisId, UUID sectionId, String videoId) {}
    Analysis analysis(String owner, String title) {
        UUID batch = UUID.randomUUID(), item = UUID.randomUUID(), upload = UUID.randomUUID(), job = UUID.randomUUID();
        UUID result = UUID.randomUUID(), record = UUID.randomUUID(), section = UUID.randomUUID();
        String video = UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        jdbc.update("insert into videos(video_id,title,duration_ms) values (?,?,10000)", video, title);
        jdbc.update("insert into analysis_batches(id,owner_id,created_at) values (?,?,?)", batch, owner, Timestamp.from(NOW));
        jdbc.update("insert into analysis_batch_items(id,batch_id,video_id,action,status,upload_deadline_at,updated_at) values (?,?,?,'COLLECT_CAPTIONS','AWAITING_UPLOAD',?,?)",
                item, batch, video, Timestamp.from(NOW.plusSeconds(1800)), Timestamp.from(NOW));
        jdbc.update("""
                insert into caption_uploads(id,owner_id,batch_item_id,video_id,bucket,object_key,content_type,declared_size_bytes,url_expires_at,generation,status)
                values (?,?,?,?,'test-bucket',?,'application/json',100,?,7,'CONSUMED')
                """, upload, owner, item, video, upload + ".json", Timestamp.from(NOW.plusSeconds(600)));
        jdbc.update("""
                insert into analysis_jobs(id,owner_id,batch_id,batch_item_id,video_id,upload_id,generation,status,stage,
                  attempt,max_attempts,analysis_id,record_id,created_at,updated_at)
                values (?,?,?,?,?,?,7,'COMPLETED','FINISHING',1,3,?,?,?,?)
                """, job, owner, batch, item, video, upload, result, record, Timestamp.from(NOW), Timestamp.from(NOW));
        String json = """
                {"schemaVersion":"1.0","summary":{"oneLine":"근거 기반 요약","keyPoints":[],"practicalPoints":[],
                "mentionedTools":[],"keywords":[{"keywordId":"33333333-3333-4333-8333-333333333333","name":"RAG"}],"highlightSectionIds":[]},
                "sections":[{"sectionId":"%s","title":"근거 검색","startMs":0,"endMs":1000,"summary":"검색 요약","subtopics":[],"keywords":[],
                "detail":{"takeaway":"구간 핵심","explanation":"설명","applicationTips":[],"recommendedTools":[],"usageKeywords":[],"examples":[]}}],
                "mindmap":{"rootTitle":"검색","sectionIds":["%s"]}}
                """.formatted(section, section);
        jdbc.update("insert into analysis_results(id,owner_id,video_id,source_job_id,result_json,chat_ready,created_at,completed_at) values (?,?,?,?,?,true,?,?)",
                result, owner, video, job, json, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("insert into analysis_records(id,owner_id,video_id,analysis_id,analyzed_at) values (?,?,?,?,?)", record, owner, video, result, Timestamp.from(NOW));
        jdbc.update("insert into analysis_sections(id,analysis_id,title,start_ms,end_ms,detail_json) values (?,?,'근거 검색',0,1000,'{}')", section, result);
        return new Analysis(record, result, section, video);
    }
    void source(UUID message, Analysis analysis) {
        jdbc.update("""
                insert into chat_message_sources(id,message_id,source_id,record_id,analysis_id,section_id,video_id,title,start_ms,end_ms,analyzed_at)
                values (?,?,'src-1',?,?,?,?,'출처 원본 제목',0,1000,?)
                """, UUID.randomUUID(), message, analysis.recordId(), analysis.analysisId(), analysis.sectionId(), analysis.videoId(), Timestamp.from(NOW));
        jdbc.update("update chat_messages set used_record_count=1 where id=?", message);
    }
}
