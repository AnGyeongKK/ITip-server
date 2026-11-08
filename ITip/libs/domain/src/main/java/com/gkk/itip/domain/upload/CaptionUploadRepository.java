package com.gkk.itip.domain.upload;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CaptionUploadRepository extends JpaRepository<CaptionUpload, UUID> {
    Optional<CaptionUpload> findByIdAndOwnerId(UUID id, String ownerId);
}
