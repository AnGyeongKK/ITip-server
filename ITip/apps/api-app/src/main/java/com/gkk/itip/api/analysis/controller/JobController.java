package com.gkk.itip.api.analysis.controller;

import com.gkk.itip.api.analysis.dto.request.RetryJobRequest;
import com.gkk.itip.api.analysis.dto.request.SubmitJobRequest;
import com.gkk.itip.api.analysis.dto.response.*;
import com.gkk.itip.api.analysis.service.JobService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import com.gkk.itip.domain.analysis.JobStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analysis-jobs")
public class JobController {
    private final JobService jobs;
    private final DemoUserProvider user;
    public JobController(JobService jobs, DemoUserProvider user) { this.jobs = jobs; this.user = user; }

    @PostMapping
    public ResponseEntity<ApiResponse<JobSubmittedResponse>> submit(@RequestHeader("Idempotency-Key") UUID key,
                                                                   @Valid @RequestBody SubmitJobRequest request) {
        var response = jobs.submit(user.ownerId(), key, request);
        return ResponseEntity.status(response.status() == JobStatus.COMPLETED ? 200 : 202)
                .location(URI.create(response.statusUrl())).body(ApiResponse.success(response));
    }

    @GetMapping("/{jobId}")
    public ApiResponse<JobStatusResponse> get(@PathVariable UUID jobId) {
        return ApiResponse.success(jobs.get(user.ownerId(), jobId));
    }

    @PostMapping("/{jobId}/retries")
    public ResponseEntity<ApiResponse<JobRetriedResponse>> retry(@PathVariable UUID jobId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody RetryJobRequest request) {
        var response = jobs.retry(user.ownerId(), key, jobId);
        return ResponseEntity.status(response.status() == JobStatus.COMPLETED ? 200 : 202)
                .location(URI.create(response.statusUrl())).body(ApiResponse.success(response));
    }
}
