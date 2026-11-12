package com.gkk.itip.api.upload;

import com.gkk.itip.api.upload.controller.CaptionUploadController;
import com.gkk.itip.api.upload.service.CaptionUploadService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.storage.GcsAccessException;
import com.gkk.itip.common.storage.GcsObjects;
import com.gkk.itip.common.web.ApiExceptionHandler;
import com.gkk.itip.domain.analysis.AnalysisBatch;
import com.gkk.itip.domain.analysis.AnalysisBatchItem;
import com.gkk.itip.domain.analysis.AnalysisBatchItemRepository;
import com.gkk.itip.domain.analysis.AnalysisBatchRepository;
import com.gkk.itip.domain.analysis.AnalysisOwnerLock;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.upload.CaptionUploadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.gkk.itip.common.storage.GcsAccessException.Reason.OBJECT_NOT_FOUND;
import static com.gkk.itip.common.storage.GcsAccessException.Reason.STORAGE_UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CaptionUploadApiTest {
    private static final String BASE = "/api/v1/caption-uploads";
    private static final String OWNER = "demo-user";
    private static final String VIDEO = "dQw4w9WgXcQ";
    private static final String BUCKET = "caption-test";
    private static final String SIGNED_URL = "https://storage.googleapis.com/caption-test/signed";
    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");

    private final AnalysisBatchRepository batches = mock(AnalysisBatchRepository.class);
    private final AnalysisBatchItemRepository items = mock(AnalysisBatchItemRepository.class);
    private final CaptionUploadRepository uploads = mock(CaptionUploadRepository.class);
    private final GcsObjects objects = mock(GcsObjects.class);
    private final AnalysisBatch batch = new AnalysisBatch(OWNER, null, NOW);
    private final AnalysisBatchItem item = AnalysisBatchItem.collectCaptions(batch.getId(), VIDEO, NOW.plusSeconds(120), NOW);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var service = new CaptionUploadService(batches, items, uploads, mock(AnalysisOwnerLock.class), objects,
                transactions, Clock.fixed(NOW, ZoneOffset.UTC), BUCKET, Duration.ofMinutes(10), 1024);
        mvc = MockMvcBuilders.standaloneSetup(new CaptionUploadController(service, new DemoUserProvider(OWNER)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        when(batches.findByIdAndOwnerId(batch.getId(), OWNER)).thenReturn(Optional.of(batch));
        when(items.findByIdAndBatchId(item.getId(), batch.getId())).thenReturn(Optional.of(item));
    }

    @Test
    void issuesFreshObjectKeysOnRetryAndCapsExpiryAtBatchDeadline() throws Exception {
        when(objects.signPut(eq(BUCKET), anyString(), eq("application/json"), eq(15L), eq(120L)))
                .thenReturn(new GcsObjects.SignedPut(SIGNED_URL, Map.of("Content-Type", "application/json")));

        String firstLocation = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(request(15)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.method").value("PUT"))
                .andExpect(jsonPath("$.data.uploadUrl").value(SIGNED_URL))
                .andExpect(jsonPath("$.data.headers['Content-Type']").value("application/json"))
                .andExpect(jsonPath("$.data.maxBytes").value(1024))
                .andReturn().getResponse().getHeader("Location");
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(request(15)))
                .andExpect(status().isCreated());

        var saved = ArgumentCaptor.forClass(CaptionUpload.class);
        verify(uploads, times(2)).save(saved.capture());
        var first = saved.getAllValues().getFirst();
        assertThat(firstLocation).isEqualTo(BASE + "/" + first.getId());
        assertThat(first.getUrlExpiresAt()).isEqualTo(item.getUploadDeadlineAt());
        assertThat(first.getObjectKey()).isNotEqualTo(saved.getValue().getObjectKey());
        assertThat(first.getBatchItemId()).isEqualTo(saved.getValue().getBatchItemId());
    }

    @Test
    void rejectsTooLargeFilesAndItemsNoLongerAwaitingUpload() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(request(1025)))
                .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("CAPTION_FILE_TOO_LARGE"));
        item.submit(UUID.randomUUID(), NOW);
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(request(15)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BATCH_ITEM_NOT_UPLOADABLE"));
        verifyNoInteractions(objects, uploads);
    }

    @Test
    void derivesStatusFromGcsAndKeepsAnUploadedObjectUsableAfterUrlExpiry() throws Exception {
        var upload = upload(NOW);
        when(objects.metadata(BUCKET, "caption.json", null))
                .thenThrow(new GcsAccessException(OBJECT_NOT_FOUND, false))
                .thenReturn(new GcsObjects.Metadata(7, 15, "application/json"))
                .thenReturn(new GcsObjects.Metadata(7, 16, "application/json"));
        mvc.perform(get(BASE + "/" + upload.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXPIRED"));
        mvc.perform(get(BASE + "/" + upload.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.jobId").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get(BASE + "/" + upload.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
        verify(uploads, never()).save(any());
    }

    @Test
    void distinguishesWaitingForPutFromStorageOutage() throws Exception {
        var upload = upload(NOW.plusSeconds(120));
        when(objects.metadata(BUCKET, "caption.json", null))
                .thenThrow(new GcsAccessException(OBJECT_NOT_FOUND, false))
                .thenThrow(new GcsAccessException(STORAGE_UNAVAILABLE, true));
        mvc.perform(get(BASE + "/" + upload.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ISSUED"));
        mvc.perform(get(BASE + "/" + upload.getId()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    }

    @Test
    void returnsJobIdForConsumedUploadAndHidesUnknownUploads() throws Exception {
        var upload = upload(NOW);
        var jobId = UUID.randomUUID();
        upload.consume(7);
        item.submit(jobId, NOW);
        when(items.findById(item.getId())).thenReturn(Optional.of(item));
        mvc.perform(get(BASE + "/" + upload.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONSUMED"))
                .andExpect(jsonPath("$.data.jobId").value(jobId.toString()));
        mvc.perform(get(BASE + "/" + UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        verifyNoInteractions(objects);
    }

    private CaptionUpload upload(Instant expiry) {
        var upload = CaptionUpload.issued(OWNER, item.getId(), VIDEO, BUCKET, "caption.json", "application/json", 15, expiry);
        when(uploads.findByIdAndOwnerId(upload.getId(), OWNER)).thenReturn(Optional.of(upload));
        return upload;
    }

    private String request(long size) {
        return """
                {"batchId":"%s","batchItemId":"%s","videoId":"%s","contentType":"application/json","sizeBytes":%d}
                """.formatted(batch.getId(), item.getId(), VIDEO, size);
    }
}
