package com.gkk.itip.api.video.controller;

import com.gkk.itip.api.video.dto.response.YoutubePlaylistResponse;
import com.gkk.itip.api.video.service.YoutubePlaylistService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/youtube-playlists")
public class YoutubePlaylistController {

    private final YoutubePlaylistService playlists;
    private final DemoUserProvider user;

    public YoutubePlaylistController(YoutubePlaylistService playlists, DemoUserProvider user) {
        this.playlists = playlists;
        this.user = user;
    }

    @GetMapping("/{playlistId}/items")
    public ApiResponse<YoutubePlaylistResponse> findItems(@PathVariable("playlistId") String playlistId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return ApiResponse.success(playlists.findItems(user.ownerId(), playlistId, cursor, limit));
    }

}
