package com.gkk.itip.common.storage;

import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class GcsConfiguration {
    @Bean
    @Lazy
    Storage gcsStorage() {
        return StorageOptions.newBuilder()
                .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(5000).setReadTimeout(15000).build())
                .setRetrySettings(StorageOptions.getDefaultRetrySettings().toBuilder()
                        .setMaxAttempts(1).setTotalTimeoutDuration(Duration.ofSeconds(30)).build())
                .build().getService();
    }

    @Bean
    GcsObjects gcsObjects(ObjectProvider<Storage> storage) { return new GcsObjects(storage::getObject); }
}
