package com.gkk.itip.api.archive.controller;

import com.gkk.itip.api.archive.dto.request.AddFolderRecordsRequest;
import com.gkk.itip.api.archive.dto.request.FolderNameRequest;
import com.gkk.itip.api.archive.dto.response.FolderResponse;
import com.gkk.itip.api.archive.dto.response.FolderResponse.Created;
import com.gkk.itip.api.archive.dto.response.FolderResponse.Page;
import com.gkk.itip.api.archive.dto.response.FolderResponse.RecordsAdded;
import com.gkk.itip.api.archive.service.FolderService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/folders")
public class FolderController {
    private final FolderService folders;
    private final DemoUserProvider user;

    public FolderController(FolderService folders, DemoUserProvider user) {
        this.folders = folders;
        this.user = user;
    }

    @GetMapping
    public ApiResponse<Page> list(@RequestParam(required = false) String cursor,
                                  @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(folders.list(user.ownerId(), cursor, limit));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Created>> create(@RequestHeader("Idempotency-Key") UUID key,
                                                       @Valid @RequestBody FolderNameRequest request) {
        var folder = folders.create(user.ownerId(), key, request.name());
        return ResponseEntity.created(URI.create("/api/v1/folders/" + folder.folderId()))
                .body(ApiResponse.success(folder));
    }

    @GetMapping("/{folderId}")
    public ApiResponse<FolderResponse> get(@PathVariable UUID folderId) {
        return ApiResponse.success(folders.get(user.ownerId(), folderId));
    }

    @PatchMapping("/{folderId}")
    public ApiResponse<FolderResponse> rename(@PathVariable UUID folderId, @Valid @RequestBody FolderNameRequest request) {
        return ApiResponse.success(folders.rename(user.ownerId(), folderId, request.name()));
    }

    @DeleteMapping("/{folderId}")
    public ApiResponse<Void> delete(@PathVariable UUID folderId) {
        folders.delete(user.ownerId(), folderId);
        return ApiResponse.success("폴더를 삭제했어요.");
    }

    @PostMapping("/{folderId}/records")
    public ApiResponse<RecordsAdded> addRecords(@PathVariable UUID folderId, @Valid @RequestBody AddFolderRecordsRequest request) {
        return ApiResponse.success(folders.addRecords(user.ownerId(), folderId, request.recordIds()));
    }

    @DeleteMapping("/{folderId}/records/{recordId}")
    public ApiResponse<Void> removeRecord(@PathVariable UUID folderId, @PathVariable UUID recordId) {
        folders.removeRecord(user.ownerId(), folderId, recordId);
        return ApiResponse.success("폴더에서 기록을 제외했어요.");
    }
}
