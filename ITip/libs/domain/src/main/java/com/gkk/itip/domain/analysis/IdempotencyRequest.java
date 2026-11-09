package com.gkk.itip.domain.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotency_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRequest {
    @Id
    private UUID id;
    @Column(nullable = false, length = 64)
    private String ownerId;
    @Column(nullable = false, length = 100)
    private String operation;
    @Column(name = "request_key", nullable = false)
    private UUID key;
    @Column(nullable = false, length = 64)
    private String requestHash;
    @Column(nullable = false)
    private UUID targetId;
    @Column(nullable = false)
    private Instant createdAt;

    public IdempotencyRequest(String ownerId, String operation, UUID key, String requestHash,
                              UUID targetId, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerId = Objects.requireNonNull(ownerId);
        this.operation = Objects.requireNonNull(operation);
        this.key = Objects.requireNonNull(key);
        this.requestHash = Objects.requireNonNull(requestHash);
        this.targetId = Objects.requireNonNull(targetId);
        this.createdAt = Objects.requireNonNull(now);
    }

    public boolean matches(String requestHash) {
        return this.requestHash.equals(requestHash);
    }
}
