package com.gkk.itip.api.device.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.UUID;

public record DeviceRegistration(
        @NotNull Platform platform,
        @JsonProperty(value = "fcmToken", required = true) @Pattern(regexp = "[!-~]{1,2048}") String fcmToken,
        @NotNull Permission notificationPermission) {
    public enum Platform { ANDROID }
    public enum Permission { GRANTED, DENIED, NOT_DETERMINED }

    public record Response(UUID installationId, Permission notificationPermission, boolean registered, Instant updatedAt) {}
}
