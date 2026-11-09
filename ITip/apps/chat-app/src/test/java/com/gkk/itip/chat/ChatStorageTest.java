package com.gkk.itip.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.flyway.enabled=true")
@Testcontainers
class ChatStorageTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clear() { jdbc.execute("truncate chat_conversations cascade"); }

    @Test void storesQuestionAnswerAndIndependentSourceSnapshotAndCascadesRoomDeletion() {
        UUID room = room(), key = UUID.randomUUID(), question = question(room, key, 1);
        UUID answer = assistant(room, question, key, 2);
        UUID record = UUID.randomUUID();
        jdbc.update("""
                insert into chat_message_sources(id,message_id,source_id,record_id,analysis_id,section_id,
                  video_id,title,start_ms,end_ms,analyzed_at)
                values (?,?,'src-1',?,?,?,'dQw4w9WgXcQ','원본 제목',0,1000,now())
                """, UUID.randomUUID(), answer, record, UUID.randomUUID(), UUID.randomUUID());
        assertThat(jdbc.queryForObject("select title from chat_message_sources where record_id=?", String.class, record)).isEqualTo("원본 제목");
        assertThat(jdbc.queryForList("select role from chat_messages where conversation_id=? order by position", String.class, room))
                .containsExactly("USER", "ASSISTANT");
        jdbc.update("delete from chat_conversations where id=?", room);
        assertThat(jdbc.queryForObject("select count(*) from chat_messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from chat_message_sources", Integer.class)).isZero();
    }

    @Test void databaseRejectsSecondActiveAnswerEvenWithAnotherRequestKey() {
        UUID room = room(), key = UUID.randomUUID(), q = question(room, key, 1);
        assistant(room, q, key, 2);
        UUID otherKey = UUID.randomUUID(), otherQ = question(room, otherKey, 3);
        assertThatThrownBy(() -> assistant(room, otherQ, otherKey, 4)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void databaseRejectsDuplicateQuestionAndCrossConversationReply() {
        UUID room = room(), key = UUID.randomUUID(), q = question(room, key, 1);
        assertThatThrownBy(() -> question(room, key, 2)).isInstanceOf(DataIntegrityViolationException.class);
        UUID otherRoom = room();
        assertThatThrownBy(() -> assistant(otherRoom, q, UUID.randomUUID(), 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void completedAnswerRequiresFinishReasonTimestampAndReleasedLease() {
        UUID room = room(), key = UUID.randomUUID();
        UUID answer = assistant(room, question(room, key, 1), key, 2);
        assertThatThrownBy(() -> jdbc.update("update chat_messages set status='COMPLETED' where id=?", answer))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("update chat_messages set status='COMPLETED', finish_reason='INSUFFICIENT_EVIDENCE', completed_at=now(), generation_token=null, lease_until=null where id=?", answer);
        assertThat(jdbc.queryForObject("select status from chat_messages where id=?", String.class, answer)).isEqualTo("COMPLETED");
        UUID second = UUID.randomUUID();
        assistant(room, question(room, second, 3), second, 4);
    }

    private UUID room() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into chat_conversations(id,owner_id,scope_json,scope_label,title,created_at,updated_at) values (?,'demo-user','{}','전체 기록','새 대화',now(),now())", id);
        return id;
    }
    private UUID question(UUID room, UUID key, long position) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into chat_messages(id,conversation_id,position,role,client_request_id,request_hash,content,created_at)
                values (?,?,?,'USER',?,'hash','질문',now())
                """, id, room, position, key);
        return id;
    }
    private UUID assistant(UUID room, UUID question, UUID key, long position) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into chat_messages(id,conversation_id,position,role,client_request_id,request_hash,content,
                  reply_to_message_id,status,scope_record_count,used_record_count,retryable,generation_token,lease_until,created_at)
                values (?,?,?,'ASSISTANT',?,'hash','',?,'GENERATING',1,0,false,?,now()+interval '1 minute',now())
                """, id, room, position, key, question, UUID.randomUUID());
        return id;
    }
}
