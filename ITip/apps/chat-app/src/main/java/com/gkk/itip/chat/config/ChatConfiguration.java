package com.gkk.itip.chat.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Clock;
@Configuration(proxyBeanMethods = false)
public class ChatConfiguration {
    @Bean Clock chatClock() { return Clock.systemUTC(); }
}
