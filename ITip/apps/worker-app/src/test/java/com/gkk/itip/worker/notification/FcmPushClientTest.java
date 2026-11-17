package com.gkk.itip.worker.notification;

import com.google.api.client.json.gson.GsonFactory;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FcmPushClientTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final String TOKEN = "test-device-token";
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void sendsBatchCountsAndIncludesAnalysisIdOnlyForTheSingleSuccessDelivery() throws IOException {
        var mixed = delivery(4, 1, null);
        var analysisId = UUID.randomUUID();
        var single = delivery(1, 0, analysisId);
        var payload = mapper.readTree(GsonFactory.getDefaultInstance().toString(FcmPushClient.message(mixed, TOKEN, NOW)));
        assertThat(payload.path("token").asString()).isEqualTo(TOKEN);
        assertThat(payload.path("notification").path("title").asString()).isNotBlank();
        assertThat(payload.path("notification").path("body").asString()).isEqualTo("성공 4개, 실패 1개");
        var data = payload.path("data");
        assertThat(data.path("type").asString()).isEqualTo("ANALYSIS_BATCH_FINISHED");
        assertThat(data.path("eventId").asString()).isEqualTo(mixed.batchId().toString());
        assertThat(data.path("batchId").asString()).isEqualTo(mixed.batchId().toString());
        assertThat(data.path("completedCount").isString()).isTrue();
        assertThat(data.path("completedCount").asString()).isEqualTo("4");
        assertThat(data.path("failedCount").isString()).isTrue();
        assertThat(data.path("failedCount").asString()).isEqualTo("1");
        assertThat(data.has("analysisId")).isFalse();
        var android = payload.path("android");
        assertThat(android.path("notification").path("tag").asString()).isEqualTo(mixed.batchId().toString());
        assertThat(android.path("ttl").asString()).isEqualTo("600s");

        var singleData = mapper.readTree(GsonFactory.getDefaultInstance().toString(FcmPushClient.message(single, TOKEN, NOW)))
                .path("data");
        assertThat(singleData.path("analysisId").isString()).isTrue();
        assertThat(singleData.path("analysisId").asString()).isEqualTo(analysisId.toString());
    }

    @Test void distinguishesInvalidRegistrationsAndHonorsRetryAfter() {
        var exception = mock(FirebaseMessagingException.class);
        var response = mock(IncomingHttpResponse.class);
        when(exception.getHttpResponse()).thenReturn(response);
        when(response.getStatusCode()).thenReturn(404);
        when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED).thenReturn(null);
        assertThat(FcmPushClient.outcome(exception, NOW).status()).isEqualTo(FcmPushClient.Status.UNREGISTERED);
        assertThat(FcmPushClient.outcome(exception, NOW).status()).isEqualTo(FcmPushClient.Status.DISCARDED);

        when(response.getStatusCode()).thenReturn(429);
        when(response.getHeaders()).thenReturn(Map.of("retry-after", List.of("180")));
        assertThat(FcmPushClient.outcome(exception, NOW))
                .isEqualTo(new FcmPushClient.Outcome(FcmPushClient.Status.RETRY, Duration.ofSeconds(180)));
        when(response.getHeaders()).thenReturn(Map.of("Retry-After", List.of("Fri, 13 Nov 2026 00:03:00 GMT")));
        assertThat(FcmPushClient.outcome(exception, NOW).retryAfter()).isEqualTo(Duration.ofSeconds(180));
    }

    private static AnalysisPushQueue.Delivery delivery(int completed, int failed, UUID analysisId) {
        return new AnalysisPushQueue.Delivery(UUID.randomUUID(), UUID.randomUUID(), completed, failed,
                analysisId, UUID.randomUUID(), 1, NOW.plusSeconds(600));
    }
}
