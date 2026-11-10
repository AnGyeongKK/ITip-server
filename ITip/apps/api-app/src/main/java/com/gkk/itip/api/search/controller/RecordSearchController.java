package com.gkk.itip.api.search.controller;

import com.gkk.itip.api.search.dto.response.RecordKeywordsResponse;
import com.gkk.itip.api.search.dto.response.RecordSearchResponse;
import com.gkk.itip.api.search.service.RecordSearchService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class RecordSearchController {
    private final RecordSearchService search;
    private final DemoUserProvider user;

    public RecordSearchController(RecordSearchService search, DemoUserProvider user) {
        this.search = search;
        this.user = user;
    }

    @GetMapping("/api/v1/records")
    public ApiResponse<RecordSearchResponse> records(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "folderId", required = false) String folderId,
            @RequestParam(name = "playlistGroupId", required = false) String playlistGroupId,
            @RequestParam(name = "targetFolderId", required = false) String targetFolderId,
            @RequestParam(name = "keywordIds", required = false) List<String> keywordIds,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.success(search.records(user.ownerId(), q, folderId, playlistGroupId,
                targetFolderId, keywordIds, cursor, limit));
    }

    @GetMapping("/api/v1/record-keywords")
    public ApiResponse<RecordKeywordsResponse> keywords(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "folderId", required = false) String folderId,
            @RequestParam(name = "playlistGroupId", required = false) String playlistGroupId,
            @RequestParam(name = "keywordIds", required = false) List<String> keywordIds) {
        return ApiResponse.success(search.keywords(user.ownerId(), q, folderId, playlistGroupId, keywordIds));
    }
}
