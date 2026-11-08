package com.gkk.itip.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DemoUserProvider {
    private final String ownerId;

    public DemoUserProvider(@Value("${itip.demo.owner-id:demo-user}") String ownerId) {
        if (ownerId.isBlank() || ownerId.length() > 64) throw new IllegalArgumentException("invalid demo owner ID");
        this.ownerId = ownerId;
    }
    public String ownerId() { return ownerId; }
}
