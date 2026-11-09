package com.gkk.itip.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AnalysisApiConfiguration {
    @Bean Clock analysisClock() { return Clock.systemUTC(); }
}
