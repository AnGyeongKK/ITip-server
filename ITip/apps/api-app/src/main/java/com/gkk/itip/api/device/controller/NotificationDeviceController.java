package com.gkk.itip.api.device.controller;

import com.gkk.itip.api.device.dto.DeviceRegistration;
import com.gkk.itip.api.device.service.NotificationDevices;
import com.gkk.itip.common.auth.CurrentUser;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/devices/{installationId}")
public class NotificationDeviceController {
    private final NotificationDevices devices;
    private final CurrentUser user;

    public NotificationDeviceController(NotificationDevices devices, CurrentUser user) {
        this.devices = devices;
        this.user = user;
    }

    @PutMapping
    public ApiResponse<DeviceRegistration.Response> register(@PathVariable UUID installationId,
                                                              @Valid @RequestBody DeviceRegistration registration) {
        return ApiResponse.success(devices.register(user.ownerId(), installationId, registration));
    }

    @DeleteMapping
    public ApiResponse<Void> disconnect(@PathVariable UUID installationId) {
        devices.disconnect(user.ownerId(), installationId);
        return ApiResponse.success("알림 연결을 해제했어요.");
    }
}
