package com.gkk.itip.chat;

import com.gkk.itip.chat.config.ChatModelConfiguration;
import com.gkk.itip.chat.generation.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ChatModelConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(ChatModelConfiguration.class);
    @Test void requiresModelTokenizerAndContextWindowWhenEnabled() {
        runner.run(c->assertThat(c).doesNotHaveBean(ChatModel.class));
        runner.withPropertyValues("itip.chat.generation.enabled=true","itip.chat.generation.api-key=fixture-only-not-a-secret")
            .run(c->assertThat(c).hasFailed());
    }
    @Test void actualSdkStreamsStructuredContentAndProviderFinishMetadata() throws Exception {
        var input=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",e->{
            input.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            e.getResponseHeaders().add("Content-Type","text/event-stream");e.sendResponseHeaders(200,0);
            try(var out=e.getResponseBody()) {
                out.write(chunk("{\"answer\":\"첫 본문",null).getBytes(StandardCharsets.UTF_8));out.flush();
                out.write(chunk(" 조각\",\"citations\":[],\"evidenceStatus\":\"INSUFFICIENT_EVIDENCE\"}","stop").getBytes(StandardCharsets.UTF_8));out.flush();
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });server.start();
        try {
            runner.withPropertyValues("itip.chat.generation.enabled=true","itip.chat.generation.api-key=fixture-only-not-a-secret",
                "itip.chat.generation.model=fixture-answer","itip.chat.generation.token-encoding=O200K_BASE",
                "itip.chat.generation.context-tokens=16384","itip.chat.generation.input-token-budget=8000",
                "itip.chat.generation.max-output-tokens=1024",
                "itip.chat.generation.base-url=http://127.0.0.1:"+server.getAddress().getPort()+"/v1").run(c->{
                    assertThat(c).hasNotFailed();var responses=c.getBean(ChatModel.class).stream(new Prompt("질문")).collectList().block(Duration.ofSeconds(5));
                    assertThat(responses).hasSize(2);assertThat(responses.getLast().getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
                    var parser=new AnswerJsonStream();var deltas=new StringBuilder();responses.forEach(r->deltas.append(parser.accept(r)));
                    assertThat(deltas.toString()).isEqualTo("첫 본문 조각");
                    assertThat(input.get()).contains("\"model\":\"fixture-answer\"","\"max_completion_tokens\":1024","\"strict\":true","\"json_schema\"","\"stream\":true");
                });
        }finally{server.stop(0);}
    }
    static String chunk(String content,String finish) {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
        var choice=new java.util.LinkedHashMap<String,Object>();choice.put("index",0);choice.put("delta",java.util.Map.of("content",content));choice.put("finish_reason",finish);
        return "data: "+mapper.writeValueAsString(java.util.Map.of("id","fixture","object","chat.completion.chunk","created",1,"model","fixture-answer","choices",java.util.List.of(choice)))+"\n\n";
    }
}
