package com.gkk.itip.chat;

import com.gkk.itip.chat.conversation.dto.MessageView;
import com.gkk.itip.chat.conversation.service.MessageQueryService;
import com.gkk.itip.chat.stream.*;
import com.gkk.itip.domain.chat.FinishReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","itip.demo.owner-id=demo-user"})
@Testcontainers
@Import(ChatCompletionConcurrencyTest.Times.class)
class ChatCompletionConcurrencyTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(
        DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc; @Autowired ChatGenerationStore store; @Autowired MessageQueryService queries;
    @MockitoSpyBean ChatCitationQuery citations;
    @Autowired TestClock clock;
    @BeforeEach void resetClock(){clock.time.set(ChatTestData.NOW);}
    @TestConfiguration static class Times {
        @Bean @Primary TestClock testClock(){return new TestClock(new AtomicReference<>(ChatTestData.NOW),ZoneOffset.UTC);}
    }
    static final class TestClock extends Clock {
        final AtomicReference<Instant> time; final ZoneId zone;
        TestClock(AtomicReference<Instant> time,ZoneId zone){this.time=time;this.zone=zone;}
        @Override public Instant instant(){return time.get();}
        @Override public ZoneId getZone(){return zone;}
        @Override public Clock withZone(ZoneId zone){return new TestClock(time,zone);}
    }

    @Test void sourceDeletionWaitsUntilValidatedCompletionCommits() throws Exception {
        var f=new ChatTestData(jdbc);f.clear();var a=f.analysis("demo-user","동시 삭제 검증");
        UUID room=f.room("demo-user"),key=UUID.randomUUID(),chunk=UUID.randomUUID();
        jdbc.update("""
            insert into analysis_chunks(id,owner_id,analysis_id,section_ids,source_segment_ids,text,start_ms,end_ms,source_type,
            embedding,embedding_model,embedding_dimensions)
            values (?,'demo-user',?,array[?]::uuid[],array['segment-1'],'원문',0,1000,'CAPTION','[0.1,0.2]','fixture',2)
            """,chunk,a.analysisId(),a.sectionId());
        var ticket=store.begin("demo-user",room,key,new SendMessageRequest(key,"질문",null));
        var selected=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);
        doAnswer(call->{
            Object actual=call.callRealMethod();selected.countDown();
            if(!allowCommit.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test completion gate expired");
            return actual;
        }).when(citations).validate(eq(ticket),anyList(),anyList());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var completing=pool.submit(()->store.complete(ticket,"기록 근거",FinishReason.STOP,
                List.of(new ChatCitation(a.recordId(),chunk,a.sectionId()))));
            try {
                assertThat(selected.await(10,TimeUnit.SECONDS)).isTrue();
                var deleting=pool.submit(()->jdbc.update("/* chat-citation-delete-race */ delete from analysis_records where id=?",a.recordId()));
                boolean blocked=false;
                for(int n=0;n<80 && !deleting.isDone();n++) {
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where query like ? and wait_event_type='Lock')",
                        Boolean.class,"%chat-citation-delete-race%"));
                    if(blocked)break;
                    Thread.sleep(25);
                }
                assertThat(blocked).as("record deletion waits for citation completion transaction").isTrue();
                allowCommit.countDown();
                assertThat(completing.get(10,TimeUnit.SECONDS)).isTrue();
                assertThat(deleting.get(10,TimeUnit.SECONDS)).isEqualTo(1);
            }finally{allowCommit.countDown();}
        }
        var saved=(MessageView.Assistant)queries.get("demo-user",room,ticket.assistantMessageId());
        assertThat(saved.status().name()).isEqualTo("COMPLETED");assertThat(saved.usedRecordCount()).isEqualTo(1);
        assertThat(saved.sources().getFirst().availability()).isEqualTo("DELETED");
    }
    @Test void leaseRecoveryWaitingOnCompletionDoesNotDowngradeCommittedAnswer() throws Exception {
        var f=new ChatTestData(jdbc);f.clear();var a=f.analysis("demo-user","만료 경합 검증");
        UUID room=f.room("demo-user"),key=UUID.randomUUID(),chunk=UUID.randomUUID();
        jdbc.update("""
            insert into analysis_chunks(id,owner_id,analysis_id,section_ids,source_segment_ids,text,start_ms,end_ms,source_type,
            embedding,embedding_model,embedding_dimensions)
            values (?,'demo-user',?,array[?]::uuid[],array['segment-1'],'원문',0,1000,'CAPTION','[0.1,0.2]','fixture',2)
            """,chunk,a.analysisId(),a.sectionId());
        var ticket=store.begin("demo-user",room,key,new SendMessageRequest(key,"질문",null));
        var selected=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);
        doAnswer(call->{Object actual=call.callRealMethod();selected.countDown();
            if(!allowCommit.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test completion gate expired");return actual;
        }).when(citations).validate(eq(ticket),anyList(),anyList());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var completing=pool.submit(()->store.complete(ticket,"완료 답변",FinishReason.STOP,
                List.of(new ChatCitation(a.recordId(),chunk,a.sectionId()))));
            try {
                assertThat(selected.await(10,TimeUnit.SECONDS)).isTrue();
                clock.time.updateAndGet(t->t.plus(ChatLimits.GENERATION_LEASE).plusSeconds(1));
                var recovering=pool.submit(store::recoverExpired);
                boolean blocked=false;
                for(int n=0;n<80 && !recovering.isDone();n++) {
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where query like ? and wait_event_type='Lock')",
                        Boolean.class,"%update chat_messages set status=%"));
                    if(blocked)break;Thread.sleep(25);
                }
                assertThat(blocked).as("lease recovery waits for completion's message lock").isTrue();
                allowCommit.countDown();assertThat(completing.get(10,TimeUnit.SECONDS)).isTrue();
                assertThat(recovering.get(10,TimeUnit.SECONDS)).isZero();
            }finally{allowCommit.countDown();}
        }
        var saved=(MessageView.Assistant)queries.get("demo-user",room,ticket.assistantMessageId());
        assertThat(saved.status().name()).isEqualTo("COMPLETED");assertThat(saved.content()).isEqualTo("완료 답변");
    }
}
