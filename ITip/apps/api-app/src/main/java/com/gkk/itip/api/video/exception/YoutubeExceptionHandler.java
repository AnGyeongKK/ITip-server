package com.gkk.itip.api.video.exception;

import com.gkk.itip.common.response.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.gkk.itip.api.video.controller")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class YoutubeExceptionHandler {
    @ExceptionHandler(YoutubeLookupException.class)
    public ResponseEntity<ApiResponse<Void>> handleLookupFailure(YoutubeLookupException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiResponse<>(exception.code(), exception.getMessage(), null));
    }
}
