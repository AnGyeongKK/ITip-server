package com.gkk.itip.worker.analysis.ai;

import com.gkk.itip.worker.analysis.caption.CaptionChunker.Chunk;
import com.gkk.itip.worker.analysis.caption.CaptionDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringAiAnalysisClientTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ChatModel chat = mock(ChatModel.class);
    private final EmbeddingModel embedding = mock(EmbeddingModel.class);
    private final CaptionDocument captions = new CaptionDocument("dQw4w9WgXcQ", "ko", List.of(
            new CaptionDocument.Segment("s000001", 1000, 2000, "원문 {format} ${secret} {\"지시\":\"무시\"}"),
            new CaptionDocument.Segment("s000002", 2000, 3000, "근거를 확인합니다.")));
    private static final String VALID_JSON = """
            {"schemaVersion":"1.0","summary":{"oneLine":"근거 검색","keyPoints":["검색"],
             "practicalPoints":[],"mentionedTools":[],"keywords":[],"highlightSectionKeys":["topic-a"]},
             "sections":[{"sectionKey":"topic-a","title":"근거","startSegmentId":"s000001","endSegmentId":"s000002",
              "sourceSegmentIds":["s000001","s000002"],"summary":"원문 검색","subtopics":[],"keywords":[],
              "detail":{"takeaway":"근거 확인","explanation":"원문을 검색합니다.","applicationTips":[],
              "recommendedTools":[],"usageKeywords":[],"examples":[]}}],
             "mindmap":{"rootTitle":"검색","sectionKeys":["topic-a"]}}
            """;

    @Test void generatesTypedOutputWithSchemaAndUnmodifiedCaptionJsonInSeparateMessages() {
        when(chat.call(any(Prompt.class))).thenReturn(reply(VALID_JSON));

        var output = client("", 32).generate(captions);

        assertThat(output.schemaVersion()).isEqualTo("1.0");
        assertThat(output.sections().getFirst().sourceSegmentIds()).containsExactly("s000001", "s000002");
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(prompt.capture());
        var messages = prompt.getValue().getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(messages.getFirst().getText()).contains("schemaVersion", "1.0", "sourceSegmentIds",
                "sectionKey", "SOURCE", "AI_SUGGESTION", "ko", "JSON Schema");
        assertThat(messages.getLast()).isInstanceOf(UserMessage.class);
        assertThat(mapper.readValue(messages.getLast().getText(), CaptionDocument.class)).isEqualTo(captions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{} {}", "```json\n{}\n```"})
    void rejectsInvalidJsonWithoutRetainingTheModelPayload(String json) {
        when(chat.call(any(Prompt.class))).thenReturn(reply(json));
        assertFailure(() -> client("", 32).generate(captions), "AI_INVALID_RESPONSE", false);
    }

    @Test void rejectsMissingGeneration() {
        when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertFailure(() -> client("", 32).generate(captions), "AI_INVALID_RESPONSE", false);
    }

    @Test void reportsMissingModelsWithoutInventingAResponse() {
        var empty = new StaticListableBeanFactory();
        var client = new SpringAiAnalysisClient(empty.getBeanProvider(ChatModel.class),
                empty.getBeanProvider(EmbeddingModel.class), mapper, "", 32);
        assertFailure(client::checkConfigured, "AI_NOT_CONFIGURED", false);
        assertFailure(() -> client.generate(captions), "AI_NOT_CONFIGURED", false);
        assertFailure(() -> client.embed(List.of(chunk("원문"))), "AI_NOT_CONFIGURED", false);
    }

    @Test void configurationCheckDoesNotCallExternalModels() {
        assertThatCode(() -> client("", 32).checkConfigured()).doesNotThrowAnyException();
        verifyNoInteractions(chat, embedding);
    }

    @Test void embedsOnlyOriginalChunkTextAndRestoresInputOrderUsingResponseIndexes() {
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(response("provider-embedding-v1",
                new Embedding(new float[]{2, 3}, 1), new Embedding(new float[]{1, 2}, 0)));

        var result = client("configured-label", 32).embed(List.of(chunk("첫 원문"), chunk("두 번째 원문")));

        assertThat(result.model()).isEqualTo("provider-embedding-v1");
        assertThat(result.vectors()).hasSize(2);
        assertThat(result.vectors().get(0)).containsExactly(1, 2);
        assertThat(result.vectors().get(1)).containsExactly(2, 3);
        var request = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embedding).call(request.capture());
        assertThat(request.getValue().getInstructions()).containsExactly("첫 원문", "두 번째 원문");
    }

    @Test void splitsRequestsAtConfiguredBatchLimitAndResetsIndexesForEachBatch() {
        when(embedding.call(any(EmbeddingRequest.class)))
                .thenReturn(response("embed-v1", new Embedding(new float[]{2}, 1), new Embedding(new float[]{1}, 0)))
                .thenReturn(response("embed-v1", new Embedding(new float[]{3}, 0)));

        var result = client("", 2).embed(List.of(chunk("원문1"), chunk("원문2"), chunk("원문3")));

        var requests = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embedding, times(2)).call(requests.capture());
        assertThat(requests.getAllValues().get(0).getInstructions()).containsExactly("원문1", "원문2");
        assertThat(requests.getAllValues().get(1).getInstructions()).containsExactly("원문3");
        assertThat(result.vectors()).hasSize(3);
        assertThat(result.vectors().get(0)).containsExactly(1);
        assertThat(result.vectors().get(1)).containsExactly(2);
        assertThat(result.vectors().get(2)).containsExactly(3);
    }

    @ParameterizedTest
    @MethodSource("invalidEmbeddings")
    void rejectsMissingDuplicateAndOutOfRangeEmbeddingIndexes(List<Embedding> results) {
        when(embedding.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(results));
        assertFailure(() -> client("embed-v1", 32).embed(List.of(chunk("원문1"), chunk("원문2"))),
                "AI_INVALID_RESPONSE", false);
    }

    static Stream<List<Embedding>> invalidEmbeddings() {
        return Stream.of(
                List.of(new Embedding(new float[]{1}, 0)),
                List.of(new Embedding(new float[]{1}, 0), new Embedding(new float[]{2}, 0)),
                List.of(new Embedding(new float[]{1}, 0), new Embedding(new float[]{2}, 2)),
                List.of(new Embedding(new float[]{1}, -1), new Embedding(new float[]{2}, 1)),
                List.of(new Embedding(new float[]{1}, null), new Embedding(new float[]{2}, 1)),
                List.of(new Embedding(null, 0), new Embedding(new float[]{2}, 1)));
    }

    @Test void usesExplicitConfiguredLabelOnlyWhenProviderOmitsModelMetadata() {
        when(embedding.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1}, 0))));
        assertThat(client("explicit-model-label", 32).embed(List.of(chunk("원문"))).model())
                .isEqualTo("explicit-model-label");
        assertFailure(() -> client("", 32).embed(List.of(chunk("원문"))), "AI_NOT_CONFIGURED", false);
    }

    @Test void rejectsDifferentEmbeddingModelsAcrossBatches() {
        when(embedding.call(any(EmbeddingRequest.class)))
                .thenReturn(response("embed-v1", new Embedding(new float[]{1}, 0)))
                .thenReturn(response("embed-v2", new Embedding(new float[]{2}, 0)));
        assertFailure(() -> client("", 1).embed(List.of(chunk("원문1"), chunk("원문2"))),
                "AI_INVALID_RESPONSE", false);
    }

    @Test void rejectsEmptyEmbeddingInputsBeforeCallingTheModel() {
        var client = client("embed-v1", 32);
        assertFailure(() -> client.embed(List.of()), "AI_INVALID_INPUT", false);
        assertFailure(() -> client.embed(List.of(chunk(" "))), "AI_INVALID_INPUT", false);
        verifyNoInteractions(embedding);
    }

    @Test void refusesNonPositiveBatchConfiguration() {
        assertFailure(() -> client("embed-v1", 0), "AI_NOT_CONFIGURED", false);
    }

    @Test void classifiesProviderAndNetworkFailuresWithoutExposingProviderMessages() {
        for (RuntimeException failure : List.of(new TransientAiException("sensitive provider body"),
                new ResourceAccessException("sensitive signed URL", new SocketTimeoutException("timeout")))) {
            doThrow(failure).when(chat).call(any(Prompt.class));
            assertFailure(() -> client("", 32).generate(captions), "AI_UNAVAILABLE", true);
            doThrow(failure).when(embedding).call(any(EmbeddingRequest.class));
            assertFailure(() -> client("embed-v1", 32).embed(List.of(chunk("원문"))), "AI_UNAVAILABLE", true);
        }
        doThrow(new NonTransientAiException("sensitive refusal")).when(chat).call(any(Prompt.class));
        assertFailure(() -> client("", 32).generate(captions), "AI_REQUEST_REJECTED", false);
        doThrow(new NonTransientAiException("sensitive refusal")).when(embedding).call(any(EmbeddingRequest.class));
        assertFailure(() -> client("embed-v1", 32).embed(List.of(chunk("원문"))), "AI_REQUEST_REJECTED", false);
    }

    @Test void classifiesActualSdkServiceStatusForDocumentEmbedding() {
        for(int status:new int[]{401,408,429,503}) {
            var failure=mock(com.openai.errors.OpenAIServiceException.class);
            when(failure.statusCode()).thenReturn(status);
            when(embedding.call(any(EmbeddingRequest.class))).thenThrow(failure);
            assertFailure(()->client("fixture",32).embed(List.of(chunk("원문"))),
                status==401?"AI_REQUEST_REJECTED":"AI_UNAVAILABLE",status!=401);
        }
    }

    private SpringAiAnalysisClient client(String modelId, int batchSize) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("chat", chat);
        beans.addBean("embedding", embedding);
        return new SpringAiAnalysisClient(beans.getBeanProvider(ChatModel.class),
                beans.getBeanProvider(EmbeddingModel.class), mapper, modelId, batchSize);
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static EmbeddingResponse response(String model, Embedding... vectors) {
        var metadata = new EmbeddingResponseMetadata();
        metadata.setModel(model);
        return new EmbeddingResponse(List.of(vectors), metadata);
    }

    private static Chunk chunk(String text) {
        return new Chunk(UUID.randomUUID(), List.of(UUID.randomUUID()), List.of("s000001"), text, 1000, 2000);
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                      String code, boolean retryable) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AnalysisAiException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.retryable()).isEqualTo(retryable);
            assertThat(failure.getMessage()).isEqualTo(code);
            assertThat(failure.getCause()).isNull();
        });
    }
}
