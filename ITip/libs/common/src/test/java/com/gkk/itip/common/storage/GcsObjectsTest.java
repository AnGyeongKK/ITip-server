package com.gkk.itip.common.storage;

import com.google.cloud.ReadChannel;
import com.google.cloud.storage.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GcsObjectsTest {
    private final Storage storage = mock(Storage.class);
    private final GcsObjects objects = new GcsObjects(() -> storage);

    @Test void returnsActualMetadataAndUsesPinnedGeneration() {
        Blob blob = mock(Blob.class);
        when(blob.getGeneration()).thenReturn(71L);
        when(blob.getSize()).thenReturn(3L);
        when(blob.getContentType()).thenReturn("application/json");
        when(storage.get(BlobId.of("bucket", "captions.json", 71L))).thenReturn(blob);
        var found = objects.metadata("bucket", "captions.json", 71L);
        assertThat(found.generation()).isEqualTo(71L);
        assertThat(found.sizeBytes()).isEqualTo(3L);
        assertThat(found.contentType()).isEqualTo("application/json");
    }

    @Test void missingObjectAndUnavailableProviderHaveDistinctReasons() {
        assertThatThrownBy(() -> objects.metadata("bucket", "missing", null))
                .isInstanceOf(GcsAccessException.class).hasMessage("OBJECT_NOT_FOUND");
        when(storage.get(BlobId.of("bucket", "missing"))).thenThrow(new StorageException(503, "sensitive-provider-message"));
        assertThatThrownBy(() -> objects.metadata("bucket", "missing", null))
                .isInstanceOf(GcsAccessException.class).hasMessage("STORAGE_UNAVAILABLE");
    }

    @Test void readsOnlyPinnedVersionAndClosesChannel() throws Exception {
        ReadChannel channel = mock(ReadChannel.class);
        when(storage.reader(eq(BlobId.of("bucket", "file", 71L)), any(Storage.BlobSourceOption.class))).thenReturn(channel);
        when(channel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer target = invocation.getArgument(0); target.put(new byte[]{1,2,3}); return 3;
        }).thenReturn(-1);
        assertThat(objects.read("bucket", "file", 71L, 3)).containsExactly((byte)1,(byte)2,(byte)3);
        verify(channel).close();
    }

    @Test void enforcesActualReadLimitEvenWithoutTrustedMetadata() throws Exception {
        ReadChannel channel = mock(ReadChannel.class);
        when(storage.reader(eq(BlobId.of("bucket", "file", 71L)), any(Storage.BlobSourceOption.class))).thenReturn(channel);
        when(channel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer target = invocation.getArgument(0); target.put(new byte[]{1,2,3,4}); return 4;
        });
        assertThatThrownBy(() -> objects.read("bucket", "file", 71L, 3))
                .isInstanceOf(GcsAccessException.class).hasMessage("CAPTION_TOO_LARGE");
        verify(channel).close();
    }

    @Test void initializesCredentialProviderOnlyWhenAnOperationNeedsIt() {
        var client = new GcsObjects(() -> { throw new IllegalStateException("no credentials"); });
        assertThatThrownBy(() -> client.metadata("b", "k", null))
                .isInstanceOf(GcsAccessException.class).hasMessage("STORAGE_NOT_CONFIGURED");
    }

    @Test void preservesTransientNetworkAnd408ButDoesNotRetryForbidden() {
        assertRetryable(new StorageException(new SocketTimeoutException("timeout")), true);
        assertRetryable(new StorageException(408, "timeout"), true);
        assertRetryable(new StorageException(403, "forbidden"), false);
    }

    private void assertRetryable(StorageException failure, boolean expected) {
        doThrow(failure).when(storage).get(BlobId.of("bucket", "file"));
        var caught = catchThrowableOfType(GcsAccessException.class, () -> objects.metadata("bucket", "file", null));
        assertThat(caught.isRetryable()).isEqualTo(expected);
    }
}
