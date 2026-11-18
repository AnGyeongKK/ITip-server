package com.gkk.itip.worker.analysis.ai;

import com.gkk.itip.ai.OpenAiClients;
import com.gkk.itip.worker.analysis.caption.AnalysisCaptionGroups;
import com.gkk.itip.worker.analysis.caption.CaptionDocument;
import com.gkk.itip.worker.config.AnalysisGenerationProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static com.gkk.itip.worker.analysis.ai.SplitAnalysisFixtures.*;

/** 실제 Spring AI·SDK·HTTP transport를 로컬 공급자 대역과 연결한다. 외부 품질 검사는 아니다. */
class SplitAnalysisHttpTest {
    @Test void sendsStrictLowStageRequestsAndDecodesNativeUsageWithoutExtraCalls() throws Exception {
        var received=new CopyOnWriteArrayList<tools.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            try {
                var request=MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));received.add(request);
                var messages=request.get("messages");var input=MAPPER.readTree(messages.get(messages.size()-1).get("content").asString());
                var response=Map.of("id","chatcmpl-fixture","object","chat.completion","created",1,"model","gpt-6-luna",
                    "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",MAPPER.writeValueAsString(payload(input))),"finish_reason","stop")),
                    "usage",Map.of("prompt_tokens",100,"completion_tokens",100,"total_tokens",200));
                byte[] bytes=MAPPER.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } finally {exchange.close();}
        });server.start();
        var client=OpenAiClients.create("fixture-not-a-real-key","http://127.0.0.1:"+server.getAddress().getPort()+"/v1",Duration.ofSeconds(10),Duration.ofSeconds(10));
        try {
            var model=OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async()).options(OpenAiChatOptions.builder().maxRetries(0).build()).build();
            var beans=new StaticListableBeanFactory();beans.addBean("model",model);
            var properties=new AnalysisGenerationProperties(false,"gpt-6-luna","O200K_BASE",1050000,30000,16000,1024,4096,3072,3);
            var generator=new SplitAnalysisGenerator(beans.getBeanProvider(ChatModel.class),MAPPER,new AnalysisSchemas(MAPPER),new AnalysisCaptionGroups(MAPPER),properties);
            var result=generator.generate(new CaptionDocument("fixture-video","ko",List.of(new CaptionDocument.Segment("s1",0,3000,"원문입니다."))),3000,"제목");
            assertThat(result.sections()).hasSize(1);assertThat(result.sections().getFirst().sourceSegmentIds()).containsExactly("s1");
            assertThat(received).hasSize(3);
            assertThat(received.stream().map(r -> r.get("max_completion_tokens").asInt())).containsExactly(1024,4096,3072);
            for(var request:received) {
                assertThat(request.get("model").asString()).isEqualTo("gpt-6-luna");assertThat(request.get("reasoning_effort").asString()).isEqualTo("low");
                assertThat(request.get("response_format").get("type").asString()).isEqualTo("json_schema");
                assertThat(request.get("response_format").get("json_schema").get("strict").asBoolean()).isTrue();
            }
        } finally {client.close();server.stop(0);}
    }
}
