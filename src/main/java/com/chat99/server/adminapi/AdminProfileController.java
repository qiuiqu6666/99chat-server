package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminProfileController {

    private final AdminProfileService profileService;

    public AdminProfileController(AdminProfileService profileService) {
        this.profileService = profileService;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProfileResponse(AdminProfileService.ProfileView user) {}

    @GetMapping("/profile")
    public ProfileResponse profile(Authentication auth) {
        AdminPrincipal principal = AdminAccess.require(auth);
        return new ProfileResponse(profileService.getProfile(principal));
    }

    @PutMapping("/profile")
    public ProfileResponse updateProfile(Authentication auth,
                                         @RequestBody AdminProfileService.UpdateProfileRequest req) {
        AdminPrincipal principal = AdminAccess.require(auth);
        return new ProfileResponse(profileService.updateProfile(principal, req));
    }
}
