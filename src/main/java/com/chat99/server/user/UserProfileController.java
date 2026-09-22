package com.chat99.server.user;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserProfileController {

    private final UserProfileService profileService;

    public UserProfileController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    /**
     * 按 userId 查询用户资料。需登录；不经过 {@code POST /users/search}，不受搜人频控影响。
     */
    @GetMapping("/users/{userId}/profile")
    public UserProfileService.UserProfileView profile(Authentication auth,
                                                      @PathVariable String userId) {
        String viewerUserId = (String) auth.getPrincipal();
        return profileService.getProfile(viewerUserId, userId);
    }
}
