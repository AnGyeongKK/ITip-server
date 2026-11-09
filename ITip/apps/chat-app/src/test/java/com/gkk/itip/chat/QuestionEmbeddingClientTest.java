package com.gkk.itip.chat;

import com.gkk.itip.chat.retrieval.*;
import com.gkk.itip.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class QuestionEmbeddingClientTest {
    private QuestionEmbeddingClient client(ChatEmbeddingFixture.FixtureModel model,String fallback) {
        var beans=new StaticListableBeanFactory(Map.of("embedding",model));
        return new QuestionEmbeddingClient(beans.getBeanProvider(EmbeddingModel.class),fallback);
    }
    @Test void embedsOnlyQuestionAndUsesResponseModelIdentity() {
        var model=new ChatEmbeddingFixture.FixtureModel();
        model.response=request->{assertThat(request.getInstructions()).containsExactly("후속 질문");return ChatEmbeddingFixture.vector("actual-model",1,2);};
        var client=client(model,"fallback");
        try {
            var vector=client.embed("후속 질문");
            assertThat(vector.model()).isEqualTo("actual-model"); assertThat(vector.values()).containsExactly(1,2);
        }finally{client.close();}
    }
    @Test void rejectsZeroNanEmptyAndUnidentifiedVectorsInsteadOfSearching() {
        var model=new ChatEmbeddingFixture.FixtureModel();var client=client(model,"");
        try {
            for(float[] vector:new float[][]{{0,0},{Float.NaN,1},{Float.POSITIVE_INFINITY,1},{}}) {
                model.response=request->ChatEmbeddingFixture.vector("fixture",vector);
                assertThatThrownBy(()->client.embed("질문")).isInstanceOf(ChatRetrievalException.class);
            }
            model.response=request->ChatEmbeddingFixture.vector("",1,0);
            assertThatThrownBy(()->client.embed("질문")).isInstanceOf(ChatRetrievalException.class);
        }finally{client.close();}
    }
    @Test void responseWithoutModelUsesWorkerCompatibleConfiguredIdentity() {
        var model=new ChatEmbeddingFixture.FixtureModel();model.response=request->ChatEmbeddingFixture.vector("",1,0);
        var client=client(model,"document-model");
        try {assertThat(client.embed("질문").model()).isEqualTo("document-model");}finally{client.close();}
    }
    @Test void distinguishesPermanentProviderRejectionFromTransientFailures() {
        var model=new ChatEmbeddingFixture.FixtureModel();var client=client(model,"");
        try {
            for(RuntimeException rejected:new RuntimeException[]{
                    new org.springframework.ai.retry.NonTransientAiException("private provider detail"),
                    org.springframework.web.reactive.function.client.WebClientResponseException.create(401,"rejected",null,null,null)}) {
                model.response=request->{throw rejected;};
                assertThatThrownBy(()->client.embed("질문")).isInstanceOfSatisfying(ChatRetrievalException.class,error->assertThat(error.retryable()).isFalse());
            }
            for(RuntimeException temporary:new RuntimeException[]{
                    new org.springframework.ai.retry.TransientAiException("private provider detail"),
                    org.springframework.web.reactive.function.client.WebClientResponseException.create(429,"busy",null,null,null),
                    org.springframework.web.reactive.function.client.WebClientResponseException.create(503,"unavailable",null,null,null)}) {
                model.response=request->{throw temporary;};
                assertThatThrownBy(()->client.embed("질문")).isInstanceOfSatisfying(ChatRetrievalException.class,error->assertThat(error.retryable()).isTrue());
            }
        }finally{client.close();}
    }
    @Test void absentOrAmbiguousModelFailsConfigurationBeforeAdmission() {
        for(var beans:new StaticListableBeanFactory[]{new StaticListableBeanFactory(),
                new StaticListableBeanFactory(Map.of("a",new ChatEmbeddingFixture.FixtureModel(),"b",new ChatEmbeddingFixture.FixtureModel()))}) {
            var client=new QuestionEmbeddingClient(beans.getBeanProvider(EmbeddingModel.class),"fixture");
            try {assertThatThrownBy(client::requireConfigured).isInstanceOf(BusinessException.class);}finally{client.close();}
        }
    }
    @Test void slowProviderIsInterruptedAndFailureIsRetryable() {
        var model=new ChatEmbeddingFixture.FixtureModel();
        var interrupted=new java.util.concurrent.CountDownLatch(1);
        model.response=request->{try{Thread.sleep(30000);}catch(InterruptedException expected){interrupted.countDown();}return ChatEmbeddingFixture.vector("fixture",1,0);};
        var client=client(model,"");
        try {
            assertThatThrownBy(()->client.embed("질문")).isInstanceOfSatisfying(ChatRetrievalException.class,error->assertThat(error.retryable()).isTrue());
            assertThat(interrupted.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }catch(InterruptedException failure){throw new AssertionError(failure);}finally{client.close();}
    }
}
