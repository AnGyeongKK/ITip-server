package com.gkk.itip.worker.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
@ConditionalOnProperty(name = {"itip.push.enabled", "itip.worker.scheduling-enabled"}, havingValue = "true")
public class AnalysisPushDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AnalysisPushDispatcher.class);
    private final AnalysisPushQueue queue;
    private final FcmPushClient client;
    private final Clock clock;

    public AnalysisPushDispatcher(AnalysisPushQueue queue, FcmPushClient client, Clock clock) {
        this.queue = queue;
        this.client = client;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        var pending = queue.claim(clock.instant());
        if (pending.isEmpty()) return;
        var delivery = pending.get();
        var token = queue.currentToken(delivery);
        if (token.isEmpty() || !clock.instant().isBefore(delivery.expiresAt())) {
            queue.finish(delivery, false);
            return;
        }
        var outcome = client.send(delivery, token.get(), clock.instant());
        switch (outcome.status()) {
            case SENT -> queue.finish(delivery, true);
            case DISCARDED, CONFIGURATION_ERROR -> queue.finish(delivery, false);
            case UNREGISTERED -> {
                queue.invalidateToken(delivery, token.get());
                queue.finish(delivery, false);
            }
            case RETRY -> {
                long base = 60L << (delivery.attempts() - 1);
                var delay = Duration.ofSeconds(base + ThreadLocalRandom.current().nextLong(base / 2 + 1));
                if (outcome.retryAfter().compareTo(delay) > 0) delay = outcome.retryAfter();
                queue.retry(delivery, clock.instant().plus(delay));
            }
        }
        log.info("ANALYSIS_PUSH batchId={} attempt={} outcome={}", delivery.batchId(), delivery.attempts(), outcome.status());
    }
}
