package com.gkk.itip.domain.upload;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CaptionUploadTest {
    @Test
    void consumingAnUploadedObjectPinsItsGenerationEvenAfterSignedUrlExpiry() {
        CaptionUpload upload = CaptionUpload.issued("demo", UUID.randomUUID(), "dQw4w9WgXcQ",
                "test-bucket", "captions/test.json", "application/json", 100L,
                Instant.parse("2026-11-08T00:00:00Z"));
        upload.markUploaded(123L);
        upload.consume(123L);
        assertEquals(UploadStatus.CONSUMED, upload.getStatus());
        assertEquals(123L, upload.getGeneration());
        assertThrows(IllegalStateException.class, () -> upload.consume(124L));
    }

    @Test
    void rejectingAnUploadPreventsConsumption() {
        CaptionUpload upload = CaptionUpload.issued("demo", UUID.randomUUID(), "dQw4w9WgXcQ",
                "test-bucket", "captions/test.json", "application/json", 100L, Instant.now());
        upload.reject();
        assertThrows(IllegalStateException.class, () -> upload.consume(123L));
        assertEquals(UploadStatus.REJECTED, upload.getStatus());
    }
}
