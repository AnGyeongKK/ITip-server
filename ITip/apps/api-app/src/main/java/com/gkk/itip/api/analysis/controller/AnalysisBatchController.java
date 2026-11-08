package com.gkk.itip.api.analysis.controller;

import com.gkk.itip.api.analysis.dto.request.CreateBatchRequest;
import com.gkk.itip.api.analysis.dto.request.ReportCollectionFailureRequest;
import com.gkk.itip.api.analysis.dto.response.*;
import com.gkk.itip.api.analysis.service.AnalysisBatchService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analysis-batches")
public class AnalysisBatchController {
    private final AnalysisBatchService batches;
    private final DemoUserProvider user;
    public AnalysisBatchController(AnalysisBatchService batches, DemoUserProvider user) { this.batches = batches; this.user = user; }

    @PostMapping
    public ResponseEntity<ApiResponse<BatchCreatedResponse>> create(@RequestHeader("Idempotency-Key") UUID key,
                                                                   @Valid @RequestBody CreateBatchRequest request) {
        var response = batches.create(user.ownerId(), key, request);
        return ResponseEntity.created(URI.create("/api/v1/analysis-batches/" + response.batchId())).body(ApiResponse.success(response));
    }

    @GetMapping("/{batchId}")
    public ApiResponse<BatchStatusResponse> get(@PathVariable UUID batchId) {
        return ApiResponse.success(batches.get(user.ownerId(), batchId));
    }

    @PatchMapping("/{batchId}/items/{itemId}")
    public ApiResponse<CollectionFailureResponse> report(@PathVariable UUID batchId, @PathVariable UUID itemId,
                                                        @Valid @RequestBody ReportCollectionFailureRequest request) {
        return ApiResponse.success(batches.reportCollectionFailure(user.ownerId(), batchId, itemId, request));
    }
}
