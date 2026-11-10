package com.gkk.itip.api.upload.controller;

import com.gkk.itip.api.upload.dto.request.CreateCaptionUploadRequest;
import com.gkk.itip.api.upload.dto.response.CaptionUploadCreatedResponse;
import com.gkk.itip.api.upload.dto.response.CaptionUploadStatusResponse;
import com.gkk.itip.api.upload.service.CaptionUploadService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/caption-uploads")
public class CaptionUploadController {
    private final CaptionUploadService uploads;
    private final DemoUserProvider user;

    public CaptionUploadController(CaptionUploadService uploads, DemoUserProvider user) {
        this.uploads = uploads;
        this.user = user;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CaptionUploadCreatedResponse>> create(
            @Valid @RequestBody CreateCaptionUploadRequest request) {
        var response = uploads.create(user.ownerId(), request);
        return ResponseEntity.created(URI.create("/api/v1/caption-uploads/" + response.uploadId()))
                .cacheControl(CacheControl.noStore()).body(ApiResponse.success(response));
    }

    @GetMapping("/{uploadId}")
    public ResponseEntity<ApiResponse<CaptionUploadStatusResponse>> get(@PathVariable("uploadId") UUID uploadId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(uploads.get(user.ownerId(), uploadId)));
    }
}
