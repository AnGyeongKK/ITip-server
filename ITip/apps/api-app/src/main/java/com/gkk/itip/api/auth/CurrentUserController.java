package com.gkk.itip.api.auth;

import com.gkk.itip.common.auth.CurrentUser;
import com.gkk.itip.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentUserController {
    private final CurrentUser user;

    public CurrentUserController(CurrentUser user) {
        this.user = user;
    }

    @GetMapping("/api/v1/users/me")
    public ApiResponse<UserResponse> currentUser() {
        return ApiResponse.success(new UserResponse(user.ownerId()));
    }

    public record UserResponse(String userId) {}
}
