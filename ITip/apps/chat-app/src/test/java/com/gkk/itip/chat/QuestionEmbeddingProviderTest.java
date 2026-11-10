package com.gkk.itip.chat;

import com.gkk.itip.ai.OpenAiEmbeddingConfiguration;
import com.gkk.itip.chat.retrieval.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class QuestionEmbeddingProviderTest {
    @Test void actualSdkErrorsReachQueryRetryClassificationWithoutRepeatedCalls() throws Exception {
        var calls=new AtomicInteger();var status=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",e->{
            calls.incrementAndGet();e.getRequestBody().readAllBytes();
            byte[] body="{\"error\":{\"message\":\"fixture provider rejection\",\"type\":\"fixture_error\"}}".getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status.get(),body.length);
            try(var out=e.getResponseBody()){out.write(body);}
        });server.start();
        try {
            new ApplicationContextRunner().withUserConfiguration(OpenAiEmbeddingConfiguration.class).withPropertyValues(
                "itip.ai.embedding.enabled=true","itip.ai.embedding.api-key=fixture-only-not-a-secret",
                "itip.ai.embedding.model=fixture","itip.ai.embedding.dimensions=2",
                "itip.ai.embedding.base-url=http://127.0.0.1:"+server.getAddress().getPort()+"/v1").run(c->{
                var client=new QuestionEmbeddingClient(c.getBeanProvider(EmbeddingModel.class),"");
                try {
                    for(int http:new int[]{401,408,429,503}) {
                        calls.set(0);status.set(http);
                        assertThatThrownBy(()->client.embed("질문")).isInstanceOfSatisfying(ChatRetrievalException.class,
                            error->assertThat(error.retryable()).isEqualTo(http!=401));
                        assertThat(calls.get()).isEqualTo(1);
                    }
                }finally{client.close();}
            });
        }finally{server.stop(0);}
    }
}
