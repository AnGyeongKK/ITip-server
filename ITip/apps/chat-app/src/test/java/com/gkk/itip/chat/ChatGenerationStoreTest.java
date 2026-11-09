package com.gkk.itip.chat;

import com.gkk.itip.chat.stream.ChatGenerationStore;
import com.gkk.itip.chat.stream.SendMessageRequest;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.chat.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.flyway.enabled=true", "itip.demo.owner-id=demo-user"})
@Testcontainers
class ChatGenerationStoreTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ChatGenerationStore store; @Autowired JdbcTemplate jdbc;
    @Autowired ChatMessageRepository messages;
    ChatTestData f; UUID room;
    @BeforeEach void clear() { f=new ChatTestData(jdbc); f.clear(); f.analysis("demo-user","RAG"); room=f.room("demo-user"); }
    SendMessageRequest request(UUID key, String text) { return new SendMessageRequest(key,text,null); }

    @Test void savesPairAtomicallyAndRejectsDuplicateOrChangedBody() {
        UUID key=UUID.randomUUID(); var r=request(key,"설명해 줘"); var accepted=store.begin("demo-user",room,key,r);
        var pair=messages.findByConversationIdAndClientRequestIdOrderByPositionAsc(room,key);
        assertThat(pair).hasSize(2); assertThat(pair.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(pair.get(1).getReplyToMessageId()).isEqualTo(pair.get(0).getId());
        assertThat(pair.get(1).getScopeRecordCount()).isEqualTo(1);
        assertThat(accepted.assistantMessageId()).isEqualTo(pair.get(1).getId());
        assertThatThrownBy(()->store.begin("demo-user",room,key,r)).isInstanceOf(BusinessException.class).hasMessageContaining("이미 접수");
        assertThatThrownBy(()->store.begin("demo-user",room,key,request(key,"다른 질문"))).isInstanceOf(BusinessException.class).hasMessageContaining("다른 요청");
        assertThat(messages.count()).isEqualTo(2);
    }
    @Test void oneConcurrentQuestionWinsAndPositionsRemainOrdered() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1); Callable<Boolean> send=()->{gate.await(); UUID key=UUID.randomUUID();
                try {store.begin("demo-user",room,key,request(key,"질문")); return true;}
                catch(BusinessException e){return false;}};
            var a=pool.submit(send); var b=pool.submit(send); gate.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForList("select position from chat_messages order by position",Long.class)).containsExactly(1L,2L);
    }
    @Test void ownerEmptyScopeAndKeyMismatchLeaveNoMessages() {
        UUID key=UUID.randomUUID();
        assertThatThrownBy(()->store.begin("other-user",room,key,request(key,"질문"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->store.begin("demo-user",room,UUID.randomUUID(),request(key,"질문"))).isInstanceOf(BusinessException.class);
        jdbc.update("delete from analysis_records");
        assertThatThrownBy(()->store.begin("demo-user",room,key,request(key,"질문"))).isInstanceOf(BusinessException.class);
        assertThat(messages.count()).isZero();
    }
    @Test void expiredLeaseReleasesRoomAndRejectsStaleCheckpoint() {
        UUID key=UUID.randomUUID(); var old=store.begin("demo-user",room,key,request(key,"질문"));
        jdbc.update("update chat_messages set content='남은 조각',lease_until=now()-interval '1 second' where id=?",old.assistantMessageId());
        assertThat(store.recoverExpired()).isEqualTo(1);
        assertThat(messages.findById(old.assistantMessageId()).orElseThrow().getStatus()).isEqualTo(MessageStatus.INCOMPLETE);
        assertThat(store.checkpoint(old,"늦은 조각")).isFalse();
        UUID next=UUID.randomUUID(); assertThat(store.begin("demo-user",room,next,request(next,"새 질문"))).isNotNull();
    }
    @Test void retryUsesNewPairAndOnlyRetryableSameRoomAnswer() {
        UUID key=UUID.randomUUID(); var old=store.begin("demo-user",room,key,request(key,"질문"));
        store.fail(old,"부분 답변","CHAT_STREAM_FAILED",true);
        UUID retryKey=UUID.randomUUID(); var retry=store.begin("demo-user",room,retryKey,
                new SendMessageRequest(retryKey,"질문",old.assistantMessageId()));
        assertThat(messages.findById(retry.assistantMessageId()).orElseThrow().getRetryOfMessageId()).isEqualTo(old.assistantMessageId());
        assertThat(messages.findById(old.assistantMessageId()).orElseThrow().getContent()).isEqualTo("부분 답변");
        store.fail(retry,"","CHAT_STREAM_FAILED",true);
        UUID invalid=UUID.randomUUID();
        assertThatThrownBy(()->store.begin("demo-user",room,invalid,new SendMessageRequest(invalid,"질문",old.userMessageId())))
                .isInstanceOf(BusinessException.class);
    }
}
