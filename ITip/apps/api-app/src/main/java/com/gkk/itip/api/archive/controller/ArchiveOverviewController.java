package com.gkk.itip.api.archive.controller;

import com.gkk.itip.api.archive.dto.response.ArchiveHomeResponse;
import com.gkk.itip.api.archive.dto.response.PlaylistGroupResponse;
import com.gkk.itip.api.archive.service.ArchiveHomeQuery;
import com.gkk.itip.api.archive.service.PlaylistGroupQuery;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ArchiveOverviewController {
    private final PlaylistGroupQuery groups;
    private final ArchiveHomeQuery home;
    private final DemoUserProvider user;

    public ArchiveOverviewController(PlaylistGroupQuery groups, ArchiveHomeQuery home, DemoUserProvider user) {
        this.groups = groups;
        this.home = home;
        this.user = user;
    }

    @GetMapping("/api/v1/playlist-groups")
    public ApiResponse<PlaylistGroupResponse.Page> groups(@RequestParam(required = false) String cursor,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(groups.list(user.ownerId(), cursor, limit));
    }

    @GetMapping("/api/v1/playlist-groups/{groupId}")
    public ApiResponse<PlaylistGroupResponse> group(@PathVariable UUID groupId) {
        return ApiResponse.success(groups.get(user.ownerId(), groupId));
    }

    @GetMapping("/api/v1/users/me/home")
    public ApiResponse<ArchiveHomeResponse> home() {
        return ApiResponse.success(home.get(user.ownerId()));
    }
}
