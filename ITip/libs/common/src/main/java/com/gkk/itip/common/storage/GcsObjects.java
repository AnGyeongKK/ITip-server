package com.gkk.itip.common.storage;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

import static com.gkk.itip.common.storage.GcsAccessException.Reason.*;

/** API의 객체 확인과 Worker의 고정 버전 읽기가 공유하는 GCS 통신 경계. */
public class GcsObjects {
    private final Supplier<Storage> provider;

    public GcsObjects(Supplier<Storage> provider) { this.provider = provider; }

    public record Metadata(long generation, long sizeBytes, String contentType) {}

    public Metadata metadata(String bucket, String objectKey, Long generation) {
        try {
            var blob = storage().get(generation == null ? BlobId.of(bucket, objectKey) : BlobId.of(bucket, objectKey, generation));
            if (blob == null) throw new GcsAccessException(OBJECT_NOT_FOUND, false);
            if (blob.getGeneration() == null || blob.getGeneration() <= 0 || blob.getSize() == null || blob.getSize() < 0)
                throw new GcsAccessException(INVALID_OBJECT_METADATA, false);
            if (generation != null && !generation.equals(blob.getGeneration()))
                throw new GcsAccessException(OBJECT_VERSION_CHANGED, false);
            return new Metadata(blob.getGeneration(), blob.getSize(), blob.getContentType());
        } catch (StorageException exception) { throw translate(exception); }
    }

    public byte[] read(String bucket, String objectKey, long generation, long maxBytes) {
        if (generation <= 0 || maxBytes <= 0 || maxBytes >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("positive generation and bounded maxBytes required");
        try (var channel = storage().reader(BlobId.of(bucket, objectKey, generation), Storage.BlobSourceOption.generationMatch(generation))) {
            var output = new ByteArrayOutputStream();
            var buffer = ByteBuffer.allocate((int)Math.min(8192, maxBytes + 1));
            int count;
            while ((count = channel.read(buffer)) != -1) {
                if ((long)output.size() + count > maxBytes) throw new GcsAccessException(CAPTION_TOO_LARGE, false);
                output.write(buffer.array(), 0, count);
                buffer.clear();
            }
            return output.toByteArray();
        } catch (StorageException exception) { throw translate(exception); }
        catch (IOException exception) { throw new GcsAccessException(STORAGE_UNAVAILABLE, true); }
    }

    private Storage storage() {
        try { return provider.get(); }
        catch (RuntimeException exception) { throw new GcsAccessException(STORAGE_NOT_CONFIGURED, false); }
    }

    private GcsAccessException translate(StorageException exception) {
        if (exception.getCode() == 404) return new GcsAccessException(OBJECT_NOT_FOUND, false);
        if (exception.getCode() == 412) return new GcsAccessException(OBJECT_VERSION_CHANGED, false);
        return new GcsAccessException(STORAGE_UNAVAILABLE,
                exception.isRetryable() || exception.getCode() == 408 || exception.getCode() == 429 || exception.getCode() >= 500);
    }
}
