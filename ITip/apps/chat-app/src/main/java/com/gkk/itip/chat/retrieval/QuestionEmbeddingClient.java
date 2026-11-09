package com.gkk.itip.chat.retrieval;

import com.gkk.itip.chat.stream.ChatLimits;
import com.gkk.itip.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.embedding.*;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.*;
import static com.gkk.itip.common.exception.ErrorCode.CHAT_AI_NOT_CONFIGURED;

@Component
public class QuestionEmbeddingClient {
    public record Vector(String model, float[] values) {
        public Vector { values = values.clone(); }
        @Override public float[] values() { return values.clone(); }
    }
    private final ObjectProvider<EmbeddingModel> models;
    private final String fallbackModel;
    // 공급자가 interrupt를 무시하더라도 추가 작업을 쌓거나 무제한 스레드를 만들지 않는다.
    private final ExecutorService calls = new ThreadPoolExecutor(ChatLimits.CONCURRENT_STREAMS,
        ChatLimits.CONCURRENT_STREAMS, 0, TimeUnit.SECONDS, new SynchronousQueue<>(),
        Thread.ofPlatform().daemon().name("chat-embedding-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());

    public QuestionEmbeddingClient(ObjectProvider<EmbeddingModel> models,
            @Value("${itip.chat.embedding-model-id:}") String fallbackModel) {
        this.models = models; this.fallbackModel = fallbackModel.strip();
    }
    public void requireConfigured() { model(); }
    private EmbeddingModel model() {
        try { var model = models.getIfUnique(); if (model != null) return model; }
        catch (BeansException ignored) { }
        throw new BusinessException(CHAT_AI_NOT_CONFIGURED);
    }
    public Vector embed(String question) {
        var model = model();
        Future<EmbeddingResponse> call;
        try { call = calls.submit(() -> model.call(new EmbeddingRequest(List.of(question), EmbeddingOptions.builder().build()))); }
        catch (RejectedExecutionException busy) { throw new ChatRetrievalException(true); }
        try {
            var response = call.get(ChatLimits.EMBEDDING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (response == null || response.getResults() == null || response.getResults().size() != 1)
                throw new ChatRetrievalException(false);
            var result = response.getResults().getFirst();
            if (result == null || result.getIndex() == null || result.getIndex() != 0 || result.getOutput() == null)
                throw new ChatRetrievalException(false);
            String id = response.getMetadata() == null ? null : response.getMetadata().getModel();
            if (id == null || id.isBlank()) id = fallbackModel;
            if (id.isBlank()) throw new ChatRetrievalException(false);
            float[] values = result.getOutput(); double norm = 0;
            if (values.length == 0 || values.length > 16000) throw new ChatRetrievalException(false);
            for (float value : values) { if (!Float.isFinite(value)) throw new ChatRetrievalException(false); norm += (double)value * value; }
            if (norm == 0) throw new ChatRetrievalException(false);
            return new Vector(id, values);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new ChatRetrievalException(true);
        } catch (TimeoutException failure) { throw new ChatRetrievalException(true); }
        catch (ExecutionException failure) { throw new ChatRetrievalException(retryable(failure.getCause())); }
        finally { call.cancel(true); }
    }
    private boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof NonTransientAiException) return false;
            if (cause instanceof TransientAiException || cause instanceof ResourceAccessException
                    || cause instanceof java.io.IOException) return true;
            if (cause instanceof RestClientResponseException http) return temporary(http.getStatusCode().value());
            if (cause instanceof WebClientResponseException http) return temporary(http.getStatusCode().value());
            if (cause == cause.getCause()) break;
        }
        return false;
    }
    private boolean temporary(int status) { return status == 408 || status == 429 || status >= 500; }
    @PreDestroy public void close() { calls.shutdownNow(); }
}
