package com.gkk.itip.worker.config;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.prompt.Prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.chat.model.ChatModel;
import static org.assertj.core.api.Assertions.*;

class AnalysisModelConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(AnalysisModelConfiguration.class);

    @Test void staysDisabledWithoutSecretsOrModelFallbacks() {
        context.run(c -> {assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(ChatModel.class);
            assertThat(c.getBean(AnalysisGenerationProperties.class).detailOutputTokens()).isEqualTo(4096);});
    }

    @Test void requiresExplicitModelTokenizerAndCapacityWhenEnabled() {
        context.withPropertyValues("itip.worker.analysis-model.enabled=true").run(c -> assertThat(c).hasFailed());
    }

    @Test void invalidBudgetsAndConcurrencyFailBeforeExternalRequests() {
        for(String property: new String[]{"detail-concurrency=4","total-output-tokens=2000","detail-output-tokens=0","input-token-budget=0"})
            context.withPropertyValues("itip.worker.analysis-model."+property).run(c -> assertThat(c).hasFailed());
    }

    @ParameterizedTest @ValueSource(strings={"ANALYSIS_AI_ENABLED", "ANALYSIS_OPENAI_ENABLED"})
    void applicationYamlSupportsExistingAndSplitEnvironmentNames(String enableName) {
        String modelName=enableName.equals("ANALYSIS_AI_ENABLED") ? "ANALYSIS_MODEL" : "ANALYSIS_OPENAI_MODEL";
        withApplicationYaml().withPropertyValues(enableName+"=true", modelName+"=fixture-analysis",
                "OPENAI_API_KEY=fixture-only-not-a-secret", "ANALYSIS_TOKEN_ENCODING=O200K_BASE",
                "ANALYSIS_CONTEXT_TOKENS=1050000").run(c -> {
                    assertThat(c).hasNotFailed();assertThat(c).hasSingleBean(ChatModel.class);
                    var properties=c.getBean(AnalysisGenerationProperties.class);
                    assertThat(properties.enabled()).isTrue();assertThat(properties.model()).isEqualTo("fixture-analysis");
                    assertThat(properties.detailOutputTokens()).isEqualTo(4096);
                });
    }

    @Test void explicitSplitOverrideCanDisableExistingEnvironmentActivation() {
        withApplicationYaml().withPropertyValues("ANALYSIS_AI_ENABLED=true", "ANALYSIS_OPENAI_ENABLED=false")
                .run(c -> {assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(ChatModel.class);});
    }

    private ApplicationContextRunner withApplicationYaml() {
        return context.withInitializer(c -> {
            try {
                var loader=new org.springframework.boot.env.YamlPropertySourceLoader();
                for(var source:loader.load("worker-yaml",new org.springframework.core.io.ClassPathResource("application.yaml")))
                    c.getEnvironment().getPropertySources().addLast(source);
            } catch(java.io.IOException exception) {throw new IllegalStateException(exception);}
        });
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
            context.withPropertyValues("itip.worker.analysis-model.enabled=true",
                            "itip.worker.analysis-model.api-key=fixture-only-not-a-secret",
                            "itip.worker.analysis-model.model=fixture-analysis",
                            "itip.worker.analysis-model.detail-output-tokens=512",
                            "itip.worker.analysis-model.token-encoding=O200K_BASE",
                            "itip.worker.analysis-model.context-tokens=1050000",
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
