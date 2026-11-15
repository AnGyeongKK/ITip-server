package com.gkk.itip.worker.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AnalysisPushQueue {
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    public AnalysisPushQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Delivery> claim(Instant now) {
        jdbc.update("""
                update analysis_push_deliveries set state = 'DISCARDED', lease_id = null
                where state = 'PENDING' and next_attempt_at <= ? and (expires_at <= ? or attempts >= ?)
                """, atUtc(now), atUtc(now), MAX_ATTEMPTS);
        return jdbc.query("""
                with candidate as (
                    select batch_id, device_id from analysis_push_deliveries
                    where state = 'PENDING' and next_attempt_at <= ? and expires_at > ? and attempts < ?
                    order by next_attempt_at, batch_id, device_id limit 1 for update skip locked
                )
                update analysis_push_deliveries p
                set lease_id = ?, next_attempt_at = ?, attempts = p.attempts + 1
                from candidate c where p.batch_id = c.batch_id and p.device_id = c.device_id
                returning p.batch_id, p.device_id, p.completed_count, p.failed_count, p.analysis_id, p.lease_id, p.attempts, p.expires_at
                """, (row, index) -> new Delivery(row.getObject("batch_id", UUID.class), row.getObject("device_id", UUID.class),
                row.getInt("completed_count"), row.getInt("failed_count"), row.getObject("analysis_id", UUID.class), row.getObject("lease_id", UUID.class), row.getInt("attempts"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant()),
                atUtc(now), atUtc(now), MAX_ATTEMPTS, UUID.randomUUID(), atUtc(now.plus(LEASE))).stream().findFirst();
    }

    public Optional<String> currentToken(Delivery delivery) {
        return jdbc.queryForList("""
                select d.fcm_token from analysis_push_deliveries p
                join notification_devices d on d.id = p.device_id
                where p.batch_id = ? and p.device_id = ? and p.lease_id = ?
                    and d.permission = 'GRANTED' and d.fcm_token is not null
                """, String.class, delivery.batchId(), delivery.deviceId(), delivery.leaseId()).stream().findFirst();
    }

    public void invalidateToken(Delivery delivery, String token) {
        jdbc.update("update notification_devices set fcm_token = null where id = ? and fcm_token = ?",
                delivery.deviceId(), token);
    }

    public void finish(Delivery delivery, boolean sent) {
        jdbc.update("""
                update analysis_push_deliveries set state = ?, lease_id = null
                where batch_id = ? and device_id = ? and lease_id = ?
                """, sent ? "SENT" : "DISCARDED", delivery.batchId(), delivery.deviceId(), delivery.leaseId());
    }

    public void retry(Delivery delivery, Instant nextAttempt) {
        if (delivery.attempts() >= MAX_ATTEMPTS || !nextAttempt.isBefore(delivery.expiresAt())) {
            finish(delivery, false);
            return;
        }
        jdbc.update("""
                update analysis_push_deliveries set next_attempt_at = ?, lease_id = null
                where batch_id = ? and device_id = ? and lease_id = ?
                """, atUtc(nextAttempt), delivery.batchId(), delivery.deviceId(), delivery.leaseId());
    }

    private static OffsetDateTime atUtc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    public record Delivery(UUID batchId, UUID deviceId, int completedCount, int failedCount, UUID analysisId,
                           UUID leaseId, int attempts, Instant expiresAt) {}
}
