package com.gkk.itip.api.archive.controller;

import com.gkk.itip.api.archive.dto.ReadingPositionResponse;
import com.gkk.itip.api.archive.dto.RecordDetailResponse;
import com.gkk.itip.api.archive.dto.SaveReadingPositionRequest;
import com.gkk.itip.api.archive.service.RecordArchiveService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/records/{recordId}")
public class RecordArchiveController {
    private final RecordArchiveService archive;
    private final DemoUserProvider user;

    public RecordArchiveController(RecordArchiveService archive, DemoUserProvider user) {
        this.archive = archive;
        this.user = user;
    }

    @GetMapping
    public ApiResponse<RecordDetailResponse> get(@PathVariable UUID recordId) {
        return ApiResponse.success(archive.get(user.ownerId(), recordId));
    }

    @PutMapping("/reading-position")
    public ApiResponse<ReadingPositionResponse> saveReadingPosition(@PathVariable UUID recordId,
                                                                   @Valid @RequestBody SaveReadingPositionRequest request) {
        return ApiResponse.success(archive.saveReadingPosition(user.ownerId(), recordId, request));
    }

    @DeleteMapping
    public ApiResponse<Void> delete(@PathVariable UUID recordId) {
        archive.delete(user.ownerId(), recordId);
        return ApiResponse.success("분석 기록을 삭제했어요.");
    }
}
