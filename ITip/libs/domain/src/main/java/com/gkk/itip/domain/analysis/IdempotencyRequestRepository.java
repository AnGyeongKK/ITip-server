package com.gkk.itip.domain.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRequestRepository extends JpaRepository<IdempotencyRequest, UUID> {
    Optional<IdempotencyRequest> findByOwnerIdAndOperationAndKey(String ownerId, String operation, UUID key);
}
