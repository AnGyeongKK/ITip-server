package com.gkk.itip.api.video.controller;

import com.gkk.itip.api.video.dto.response.YoutubeResourceResponse;
import com.gkk.itip.api.video.service.YoutubeResourceService;
import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/youtube-resources")
public class YoutubeResourceController {

    private final YoutubeResourceService resources;
    private final DemoUserProvider user;

    public YoutubeResourceController(YoutubeResourceService resources, DemoUserProvider user) {
        this.resources = resources;
        this.user = user;
    }

    @GetMapping
    public ApiResponse<YoutubeResourceResponse> resolve(@RequestParam("url") String url) {
        return ApiResponse.success(resources.resolve(user.ownerId(), url));
    }
}
