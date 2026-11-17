package com.gkk.itip.worker.config;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

class AnalysisModelConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AnalysisModelConfiguration.class);

    @Test void enablesModelOnlyWithExplicitConfiguration() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ChatModel.class));
        runner.withPropertyValues("itip.worker.analysis-model.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("itip.worker.analysis-model.enabled=true",
                        "itip.worker.analysis-model.api-key=fixture-only-not-a-secret")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void sendsConfiguredAnalysisOptionsAndDoesNotRetryOn429() throws Exception {
        var body = new AtomicReference<String>();
        var status = new AtomicInteger(200);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = status.get() == 200
                    ? "{\"id\":\"fixture\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fixture-analysis\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}"
                    : "{\"error\":{\"message\":\"fixture failure\",\"type\":\"rate_limit_error\"}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            runner.withPropertyValues("itip.worker.analysis-model.enabled=true",
                            "itip.worker.analysis-model.api-key=fixture-only-not-a-secret",
                            "itip.worker.analysis-model.model=fixture-analysis",
                            "itip.worker.analysis-model.max-output-tokens=512",
                            "itip.worker.analysis-model.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var model = context.getBean(ChatModel.class);
                        assertThat(model.call(new Prompt("분석 요청")).getResult().getOutput().getText()).isEqualTo("answer");
                        assertThat(body.get()).contains("\"model\":\"fixture-analysis\"", "\"max_completion_tokens\":512");
                        status.set(429);
                        calls.set(0);
                        assertThatThrownBy(() -> model.call(new Prompt("분석 요청"))).isInstanceOf(RuntimeException.class);
                        assertThat(calls).hasValue(1);
                    });
        } finally {
            server.stop(0);
        }
    }
}
