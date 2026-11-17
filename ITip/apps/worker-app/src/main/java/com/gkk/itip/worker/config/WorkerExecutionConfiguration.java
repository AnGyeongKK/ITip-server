package com.gkk.itip.worker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AnalysisWorkerProperties.class)
public class WorkerExecutionConfiguration {
    @Bean Clock workerClock() { return Clock.systemUTC(); }

    @Bean ThreadPoolTaskScheduler taskScheduler(Clock clock, @Value("${itip.push.enabled:false}") boolean pushEnabled) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setClock(clock);
        scheduler.setPoolSize(pushEnabled ? 3 : 2);
        scheduler.setThreadNamePrefix("analysis-worker-");
        scheduler.setVirtualThreads(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
