package com.gkk.itip.worker.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "itip.worker.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisScheduler {
    private static final Logger log = LoggerFactory.getLogger(AnalysisScheduler.class);
    private final AnalysisWorker worker;

    public AnalysisScheduler(AnalysisWorker worker) { this.worker = worker; }

    @Scheduled(fixedDelayString = "${itip.worker.poll-delay:PT2S}")
    public void poll() {
        try { worker.runNext(); }
        catch (RuntimeException exception) {
            log.warn("WORKER_POLL_FAILED type={}", exception.getClass().getSimpleName());
        }
    }
}
