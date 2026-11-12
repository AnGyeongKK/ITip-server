package com.gkk.itip.chat.config;
import com.gkk.itip.chat.stream.ChatLimits;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration @EnableScheduling
public class ChatStreamConfiguration implements WebMvcConfigurer {
    @Bean
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ChatLimits.CONCURRENT_STREAMS); executor.setMaxPoolSize(ChatLimits.CONCURRENT_STREAMS);
        executor.setQueueCapacity(ChatLimits.CONCURRENT_STREAMS);
        executor.setThreadNamePrefix("chat-stream-");
        return executor;
    }
    @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(chatStreamExecutor()).setDefaultTimeout(ChatLimits.MVC_TIMEOUT_MS);
    }
}
