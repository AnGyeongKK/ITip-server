package com.gkk.itip.worker.notification;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class AnalysisPushDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private final AnalysisPushQueue queue = mock(AnalysisPushQueue.class);
    private final FcmPushClient client = mock(FcmPushClient.class);
    private final AnalysisPushDispatcher dispatcher = new AnalysisPushDispatcher(queue, client, Clock.fixed(NOW, ZoneOffset.UTC));
    private final AnalysisPushQueue.Delivery delivery = new AnalysisPushQueue.Delivery(
            UUID.randomUUID(), UUID.randomUUID(), 1, 0, UUID.randomUUID(), UUID.randomUUID(), 1, NOW.plusSeconds(60));

    @Test void requeuesWhenTokenRotatesDuringAnUnregisteredSend() {
        dispatchUnregistered(Optional.of("new-token"));

        verify(queue).invalidateToken(delivery, "old-token");
        verify(queue).retry(delivery, NOW);
        verify(queue, never()).finish(delivery, false);
    }

    @Test void discardsWhenUnregisteredTokenHasNoReplacement() {
        dispatchUnregistered(Optional.empty());

        verify(queue).invalidateToken(delivery, "old-token");
        verify(queue).finish(delivery, false);
        verify(queue, never()).retry(delivery, NOW);
    }

    private void dispatchUnregistered(Optional<String> refreshedToken) {
        when(queue.claim(NOW)).thenReturn(Optional.of(delivery));
        when(queue.currentToken(delivery)).thenReturn(Optional.of("old-token")).thenReturn(refreshedToken);
        when(client.send(delivery, "old-token", NOW))
                .thenReturn(new FcmPushClient.Outcome(FcmPushClient.Status.UNREGISTERED, Duration.ZERO));
        dispatcher.dispatch();
    }
}
