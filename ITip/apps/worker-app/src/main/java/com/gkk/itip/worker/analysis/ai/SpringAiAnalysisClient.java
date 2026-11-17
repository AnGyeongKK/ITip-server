package com.gkk.itip.worker.analysis.ai;

import com.gkk.itip.worker.analysis.caption.CaptionChunker.Chunk;
import com.gkk.itip.worker.analysis.caption.CaptionDocument;
import com.gkk.itip.worker.analysis.output.AnalysisOutput;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 공급자별 모듈이 제공하는 모델 Bean의 I/O만 변환하며 DB나 재시도 루프를 소유하지 않는다. */
@Component
public class SpringAiAnalysisClient {
    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<EmbeddingModel> embeddingModels;
    private final SplitAnalysisGenerator generator;
    private final String embeddingModelId;
    private final int embeddingBatchSize;

    public SpringAiAnalysisClient(ObjectProvider<ChatModel> chatModels, ObjectProvider<EmbeddingModel> embeddingModels,
                                 SplitAnalysisGenerator generator,
                                 @Value("${analysis.ai.embedding-model-id:${ANALYSIS_EMBEDDING_MODEL_ID:}}") String embeddingModelId,
                                 @Value("${analysis.ai.embedding-batch-size:${ANALYSIS_EMBEDDING_BATCH_SIZE:32}}") int embeddingBatchSize) {
        if (embeddingBatchSize <= 0) throw new AnalysisAiException("AI_NOT_CONFIGURED", false);
        this.chatModels = chatModels;
        this.embeddingModels = embeddingModels;
        this.generator = generator;
        this.embeddingModelId = embeddingModelId == null ? "" : embeddingModelId.trim();
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public record Embeddings(String model, List<float[]> vectors) {
        public Embeddings { vectors = List.copyOf(vectors); }
    }

    /** 두 모델의 구성이 유일하게 선택되는지만 확인하며 외부 호출은 하지 않는다. */
    public void checkConfigured() {
        requireModel(chatModels);
        requireModel(embeddingModels);
        generator.checkConfigured();
    }

    public AnalysisOutput generate(CaptionDocument captions, long durationMs, String title) {
        requireModel(chatModels);
        return generator.generate(captions, durationMs, title);
    }

    public Embeddings embed(List<Chunk> chunks) {
        var model = requireModel(embeddingModels);
        if (chunks == null || chunks.isEmpty() || chunks.stream().anyMatch(chunk ->
                chunk == null || chunk.text() == null || chunk.text().isBlank()))
            throw new AnalysisAiException("AI_INVALID_INPUT", false);
        var vectors = new ArrayList<float[]>(chunks.size());
        String modelId = null;
        for (int start = 0; start < chunks.size();) {
            int end = start + Math.min(embeddingBatchSize, chunks.size() - start);
            // 메타데이터·요약은 포함하지 않고 CaptionChunker가 만든 원문만 전달한다.
            var texts = chunks.subList(start, end).stream().map(Chunk::text).toList();
            EmbeddingResponse response;
            try {
                response = model.call(new EmbeddingRequest(texts, EmbeddingOptions.builder().build()));
            } catch (RuntimeException exception) {
                throw providerFailure(exception);
            }
            if (response == null || response.getResults() == null || response.getResults().size() != texts.size())
                throw invalidResponse();
            String batchModel = embeddingModel(response);
            if (modelId != null && !modelId.equals(batchModel)) throw invalidResponse();
            modelId = batchModel;
            var ordered = new float[texts.size()][];
            for (var result : response.getResults()) {
                if (result == null || result.getIndex() == null || result.getIndex() < 0
                        || result.getIndex() >= ordered.length || ordered[result.getIndex()] != null
                        || result.getOutput() == null)
                    throw invalidResponse();
                ordered[result.getIndex()] = result.getOutput();
            }
            if (Arrays.stream(ordered).anyMatch(vector -> vector == null)) throw invalidResponse();
            vectors.addAll(Arrays.asList(ordered));
            start = end;
        }
        return new Embeddings(modelId, vectors);
    }

    private String embeddingModel(EmbeddingResponse response) {
        String model = response.getMetadata() == null ? null : response.getMetadata().getModel();
        if (model != null && !model.isBlank()) return model;
        if (embeddingModelId.isBlank()) throw new AnalysisAiException("AI_NOT_CONFIGURED", false);
        return embeddingModelId;
    }

    private <T> T requireModel(ObjectProvider<T> models) {
        try {
            var model = models.getIfUnique();
            if (model != null) return model;
        } catch (BeansException exception) {
            // Bean 구성 실패 메시지에도 인증 설정 등이 포함될 수 있으므로 전달하지 않는다.
        }
        throw new AnalysisAiException("AI_NOT_CONFIGURED", false);
    }

    static AnalysisAiException providerFailure(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.openai.errors.OpenAIServiceException response) {
                boolean retryable=com.gkk.itip.ai.AiHttpRetryPolicy.retryable(response.statusCode());
                return new AnalysisAiException(retryable?"AI_UNAVAILABLE":"AI_REQUEST_REJECTED",retryable);
            }
            if (cause instanceof com.openai.errors.OpenAIIoException)
                return new AnalysisAiException("AI_UNAVAILABLE",true);
            if (cause instanceof NonTransientAiException)
                return new AnalysisAiException("AI_REQUEST_REJECTED", false);
            if (cause instanceof TransientAiException || cause instanceof ResourceAccessException || cause instanceof IOException)
                return new AnalysisAiException("AI_UNAVAILABLE", true);
            if (cause instanceof RestClientResponseException response) {
                int status = response.getStatusCode().value();
                boolean retryable = com.gkk.itip.ai.AiHttpRetryPolicy.retryable(status);
                return new AnalysisAiException(retryable ? "AI_UNAVAILABLE" : "AI_REQUEST_REJECTED", retryable);
            }
            if (cause == cause.getCause()) break;
        }
        return new AnalysisAiException("AI_REQUEST_FAILED", false);
    }

    private AnalysisAiException invalidResponse() { return new AnalysisAiException("AI_INVALID_RESPONSE", false); }
}
