package com.gkk.itip.chat;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.assertThat;

@TestConfiguration
class ChatEmbeddingFixture {
    @Bean FixtureModel fixtureEmbeddingModel() { return new FixtureModel(); }
    static class FixtureModel implements EmbeddingModel {
        volatile Function<EmbeddingRequest, EmbeddingResponse> response = request -> vector("fixture", 1, 0);
        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(request.getInstructions()).hasSize(1).allMatch(text -> !text.isBlank());
            return response.apply(request);
        }
        @Override public float[] embed(Document document) { throw new UnsupportedOperationException(); }
    }
    static EmbeddingResponse vector(String model, float... vector) {
        var metadata = new EmbeddingResponseMetadata(); metadata.setModel(model);
        return new EmbeddingResponse(List.of(new Embedding(vector, 0)), metadata);
    }
}
