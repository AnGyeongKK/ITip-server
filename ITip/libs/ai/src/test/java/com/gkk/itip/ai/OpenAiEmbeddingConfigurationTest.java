package com.gkk.itip.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class OpenAiEmbeddingConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(OpenAiEmbeddingConfiguration.class);
    @Test void disabledProviderNeedsNoKeyAndEnabledProviderRequiresExplicitModelAndDimensions() {
        runner.run(c->assertThat(c).doesNotHaveBean(EmbeddingModel.class));
        runner.withPropertyValues("itip.ai.embedding.enabled=true","itip.ai.embedding.api-key=fixture-only-not-a-secret")
            .run(c->assertThat(c).hasFailed());
    }
    @Test void actualSdkUsesConfiguredModelAndDimensionsAndDoesNotRetry408Or429() throws Exception {
        var body=new AtomicReference<String>();var status=new AtomicInteger(200);var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",e->{
            calls.incrementAndGet();body.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String json=status.get()==200?"{\"object\":\"list\",\"model\":\"fixture-embedding\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1.0,0.0]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}"
                :"{\"error\":{\"message\":\"fixture rate limit\",\"type\":\"rate_limit_error\"}}";
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");
            e.sendResponseHeaders(status.get(),bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}
        });server.start();
        try {
            runner.withPropertyValues("itip.ai.embedding.enabled=true","itip.ai.embedding.api-key=fixture-only-not-a-secret",
                "itip.ai.embedding.model=fixture-embedding","itip.ai.embedding.dimensions=2",
                "itip.ai.embedding.base-url=http://127.0.0.1:"+server.getAddress().getPort()+"/v1").run(c->{
                    assertThat(c).hasNotFailed();var model=c.getBean(EmbeddingModel.class);
                    var response=model.call(new EmbeddingRequest(List.of("원문"),EmbeddingOptions.builder().build()));
                    assertThat(response.getResult().getOutput()).containsExactly(1,0);
                    assertThat(response.getMetadata().getModel()).isEqualTo("fixture-embedding");
                    assertThat(body.get()).contains("\"model\":\"fixture-embedding\"","\"dimensions\":2","원문");
                    for(int failure:new int[]{408,429}) {
                        calls.set(0);status.set(failure);
                        assertThatThrownBy(()->model.call(new EmbeddingRequest(List.of("질문"),EmbeddingOptions.builder().build()))).isInstanceOf(RuntimeException.class);
                        assertThat(calls.get()).as("SDK and transport retries are disabled for HTTP %s",failure).isEqualTo(1);
                    }
                });
        }finally{server.stop(0);}
    }
}
