package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserPrivacyController {

    private final UserPrivacyService privacyService;

    public UserPrivacyController(UserPrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    @GetMapping("/{userId}/privacy")
    public UserPrivacyService.PrivacyView getUserPrivacy(@PathVariable String userId) {
        return privacyService.getPrivacyForUser(userId);
    }

    public record AddFriendCheckRequest(
        @NotBlank String targetUserId,
        @NotBlank String channel) {}

    @PostMapping("/add-friend/check")
    public UserPrivacyService.AddFriendCheckResult checkAddFriend(
        Authentication auth,
        @Valid @RequestBody AddFriendCheckRequest req) {
        String caller = auth == null ? null : (String) auth.getPrincipal();
        return privacyService.checkAddFriendForCaller(caller, req.targetUserId(), req.channel());
    }
}
