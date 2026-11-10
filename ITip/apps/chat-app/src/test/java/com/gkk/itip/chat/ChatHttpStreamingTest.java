package com.gkk.itip.chat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.flyway.enabled=true","itip.demo.owner-id=demo-user"})
@Testcontainers
@org.springframework.context.annotation.Import(ChatEmbeddingFixture.class)
class ChatHttpStreamingTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    static final CountDownLatch CLIENT_SAW_FIRST=new CountDownLatch(1);
    static final AtomicBoolean RELEASED_BY_CLIENT=new AtomicBoolean();
    static HttpServer model;
    @DynamicPropertySource static void configure(DynamicPropertyRegistry r) throws Exception {
        model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/v1/chat/completions",e->{
            e.getRequestBody().readAllBytes();e.getResponseHeaders().add("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);
            try(var out=e.getResponseBody()) {
                out.write(ChatModelFixture.chunk("{\"answer\":\"첫 조각",null).getBytes(StandardCharsets.UTF_8));out.flush();
                try { RELEASED_BY_CLIENT.set(CLIENT_SAW_FIRST.await(3,TimeUnit.SECONDS)); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                out.write(ChatModelFixture.chunk("\",\"citations\":[],\"evidenceStatus\":\"INSUFFICIENT_EVIDENCE\"}","stop").getBytes(StandardCharsets.UTF_8));out.flush();
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }catch(IOException disconnected){ }
        });model.start();
        ChatModelFixture.properties(r,model.getAddress().getPort());
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @AfterAll static void close(){CLIENT_SAW_FIRST.countDown();if(model!=null)model.stop(0);}
    @Autowired JdbcTemplate jdbc; @Autowired JsonMapper mapper;
    @Value("${local.server.port}") int port;

    @Test void realHttpDeliversAndStoresFirstDeltaBeforeUpstreamCompletes() throws Exception {
        var f=new ChatTestData(jdbc);f.clear();f.analysis("demo-user","실제 HTTP 테스트");
        UUID room=f.room("demo-user"),key=UUID.randomUUID();
        String path="http://localhost:"+port+"/api/v1/conversations/"+room+"/messages";
        var request=HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type","application/json").header("Accept","text/event-stream").header("Idempotency-Key",key.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"clientRequestId\":\""+key+"\",\"content\":\"질문\"}")) .build();
        var events=new ArrayList<String>();
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
            try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
                String line;
                while((line=reader.readLine())!=null) {
                    if(line.startsWith("event: ")) events.add(line.substring(7));
                    if(line.startsWith("data: ") && events.getLast().equals("delta")) {
                        assertThat(mapper.readTree(line.substring(6)).path("text").asString()).isEqualTo("첫 조각");
                        assertThat(jdbc.queryForObject("select status from chat_messages where client_request_id=? and role='ASSISTANT'",String.class,key)).isEqualTo("GENERATING");
                        assertThat(jdbc.queryForObject("select content from chat_messages where client_request_id=? and role='ASSISTANT'",String.class,key)).isEqualTo("첫 조각");
                        CLIENT_SAW_FIRST.countDown();
                    }
                }
            }finally{CLIENT_SAW_FIRST.countDown();}
        }
        assertThat(RELEASED_BY_CLIENT).isTrue();
        assertThat(events).containsExactly("meta","delta","sources","done");
        assertThat(jdbc.queryForObject("select status from chat_messages where client_request_id=? and role='ASSISTANT'",String.class,key)).isEqualTo("COMPLETED");
    }
}
