package com.gkk.itip.common.config;

import com.gkk.itip.common.auth.CurrentUser;

public class DemoUserProvider implements CurrentUser {
    private final String ownerId;

    public DemoUserProvider(String ownerId) {
        if (ownerId.isBlank() || ownerId.length() > 64) throw new IllegalArgumentException("invalid demo owner ID");
        this.ownerId = ownerId;
    }
    @Override
    public String ownerId() { return ownerId; }
}
