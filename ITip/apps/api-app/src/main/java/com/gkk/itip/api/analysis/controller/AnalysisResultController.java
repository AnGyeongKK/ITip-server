package com.gkk.itip.api.analysis.controller;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse;
import com.gkk.itip.api.analysis.service.AnalysisResultQueryService;
import com.gkk.itip.api.config.DemoUserProvider;
import com.gkk.itip.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analyses")
public class AnalysisResultController {
    private final AnalysisResultQueryService results;
    private final DemoUserProvider user;

    public AnalysisResultController(AnalysisResultQueryService results, DemoUserProvider user) {
        this.results = results;
        this.user = user;
    }

    @GetMapping("/{analysisId}")
    public ApiResponse<AnalysisResultResponse> get(@PathVariable UUID analysisId) {
        return ApiResponse.success(results.get(user.ownerId(), analysisId));
    }
}
