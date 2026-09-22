package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OnlinePrivacyProtectionController {

    private final OnlinePrivacyProtectionService onlinePrivacyService;

    public OnlinePrivacyProtectionController(OnlinePrivacyProtectionService onlinePrivacyService) {
        this.onlinePrivacyService = onlinePrivacyService;
    }

    public record OnlinePrivacyProtectionView(LastActiveVisibility lastActiveVisibility) {}

    public record OnlinePrivacyProtectionUpdateRequest(@NotNull LastActiveVisibility lastActiveVisibility) {}

    @GetMapping("/me/online-privacy-protection")
    public OnlinePrivacyProtectionView getMine(Authentication auth) {
        return onlinePrivacyService.getForSelf((String) auth.getPrincipal());
    }

    @PutMapping("/me/online-privacy-protection")
    public OnlinePrivacyProtectionView updateMine(Authentication auth,
                                                  @Valid @RequestBody OnlinePrivacyProtectionUpdateRequest req) {
        return onlinePrivacyService.updateForSelf(
            (String) auth.getPrincipal(), req.lastActiveVisibility());
    }

    @GetMapping("/users/{userId}/online-privacy-protection")
    public OnlinePrivacyProtectionView getForUser(@PathVariable String userId) {
        return onlinePrivacyService.getForUser(userId);
    }
}
