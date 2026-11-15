package com.gkk.itip.worker.notification;

import com.gkk.itip.domain.analysis.AnalysisBatchPushOutbox;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
@ConditionalOnProperty(name = {"itip.push.enabled", "itip.worker.scheduling-enabled"}, havingValue = "true")
public class FcmPushClient {
    private final String projectId;
    private FirebaseApp app;

    public FcmPushClient(@Value("${itip.push.project-id:}") String projectId) {
        this.projectId = projectId;
    }

    public Outcome send(AnalysisPushQueue.Delivery delivery, String token, Instant now) {
        if (!projectId.matches("[a-z][a-z0-9-]{4,28}[a-z0-9]")) {
            return new Outcome(Status.CONFIGURATION_ERROR, Duration.ZERO);
        }
        try {
            messaging().send(message(delivery, token, now));
            return new Outcome(Status.SENT, Duration.ZERO);
        } catch (FirebaseMessagingException exception) {
            return outcome(exception, now);
        } catch (IOException exception) {
            return new Outcome(Status.RETRY, Duration.ZERO);
        }
    }

    private synchronized FirebaseMessaging messaging() throws IOException {
        if (app == null) {
            var options = FirebaseOptions.builder()
                    .setProjectId(projectId)
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setConnectTimeout(5000)
                    .setReadTimeout(10000)
                    .setWriteTimeout(10000)
                    .build();
            app = FirebaseApp.initializeApp(options, "itip-push");
        }
        return FirebaseMessaging.getInstance(app);
    }

    @PreDestroy
    synchronized void close() {
        if (app != null) app.delete();
    }

    static Message message(AnalysisPushQueue.Delivery delivery, String token, Instant now) {
        long ttl = Math.max(0, Duration.between(now, delivery.expiresAt()).toMillis());
        String batchId = delivery.batchId().toString();
        String title = delivery.completedCount() == 0 ? "영상 분석에 실패했어요" : "영상 처리가 끝났어요";
        String body = "성공 %d개, 실패 %d개".formatted(delivery.completedCount(), delivery.failedCount());
        var message = Message.builder().setToken(token)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putData("type", "ANALYSIS_BATCH_FINISHED")
                .putData("eventId", batchId)
                .putData("batchId", batchId)
                .putData("completedCount", Integer.toString(delivery.completedCount()))
                .putData("failedCount", Integer.toString(delivery.failedCount()))
                .setAndroidConfig(AndroidConfig.builder().setTtl(ttl)
                        .setNotification(AndroidNotification.builder().setTag(batchId).build()).build());
        if (delivery.analysisId() != null) message.putData("analysisId", delivery.analysisId().toString());
        return message.build();
    }

    static Outcome outcome(FirebaseMessagingException exception, Instant now) {
        var code = exception.getMessagingErrorCode();
        if (code == MessagingErrorCode.UNREGISTERED) return new Outcome(Status.UNREGISTERED, Duration.ZERO);
        var response = exception.getHttpResponse();
        int status = response == null ? 0 : response.getStatusCode();
        if (status == 429 || status >= 500 || code == MessagingErrorCode.QUOTA_EXCEEDED
                || code == MessagingErrorCode.UNAVAILABLE || code == MessagingErrorCode.INTERNAL
                || (response == null && exception.getCause() instanceof IOException)) {
            return new Outcome(Status.RETRY, retryAfter(retryAfterHeader(response), now));
        }
        return new Outcome(Status.DISCARDED, Duration.ZERO);
    }

    private static String retryAfterHeader(IncomingHttpResponse response) {
        if (response == null) return null;
        for (var entry : response.getHeaders().entrySet()) {
            if (!entry.getKey().equalsIgnoreCase("Retry-After")) continue;
            Object value = entry.getValue();
            if (value instanceof List<?> values) value = values.isEmpty() ? null : values.getFirst();
            return value instanceof String header ? header : null;
        }
        return null;
    }

    private static Duration retryAfter(String header, Instant now) {
        if (header == null) return Duration.ZERO;
        try {
            return Duration.ofSeconds(Math.clamp(Long.parseLong(header), 0, AnalysisBatchPushOutbox.TTL.toSeconds()));
        } catch (NumberFormatException ignored) {
            try {
                var delay = Duration.between(now, ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                if (delay.isNegative()) return Duration.ZERO;
                return delay.compareTo(AnalysisBatchPushOutbox.TTL) > 0 ? AnalysisBatchPushOutbox.TTL : delay;
            } catch (DateTimeParseException exception) {
                return Duration.ZERO;
            }
        }
    }

    enum Status { SENT, RETRY, UNREGISTERED, DISCARDED, CONFIGURATION_ERROR }
    record Outcome(Status status, Duration retryAfter) {}
}
