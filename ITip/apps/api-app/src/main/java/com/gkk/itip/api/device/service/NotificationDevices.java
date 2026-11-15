package com.gkk.itip.api.device.service;

import com.gkk.itip.api.device.dto.DeviceRegistration;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.analysis.AnalysisOwnerLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.STATE_CONFLICT;

@Service
public class NotificationDevices {
    private final JdbcTemplate jdbc;
    private final AnalysisOwnerLock ownerLock;

    public NotificationDevices(JdbcTemplate jdbc, AnalysisOwnerLock ownerLock) {
        this.jdbc = jdbc;
        this.ownerLock = ownerLock;
    }

    @Transactional
    public DeviceRegistration.Response register(String ownerId, UUID installationId, DeviceRegistration registration) {
        ownerLock.lock(ownerId);
        String token = registration.notificationPermission() == DeviceRegistration.Permission.GRANTED
                ? registration.fcmToken() : null;
        try {
            var updated = jdbc.query("""
                    insert into notification_devices (id, installation_id, owner_id, platform, fcm_token, permission, updated_at)
                    values (?, ?, ?, ?, ?, ?, now())
                    on conflict (installation_id) do update set fcm_token = excluded.fcm_token,
                        permission = excluded.permission, updated_at = excluded.updated_at
                    where notification_devices.owner_id = excluded.owner_id
                    returning updated_at
                    """, (row, index) -> row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                    UUID.randomUUID(), installationId, ownerId, registration.platform().name(),
                    token, registration.notificationPermission().name());
            if (updated.isEmpty()) throw new BusinessException(STATE_CONFLICT);
            return new DeviceRegistration.Response(installationId, registration.notificationPermission(), true, updated.getFirst());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(STATE_CONFLICT);
        }
    }

    @Transactional
    public void disconnect(String ownerId, UUID installationId) {
        ownerLock.lock(ownerId);
        jdbc.update("delete from notification_devices where owner_id = ? and installation_id = ?", ownerId, installationId);
    }
}
