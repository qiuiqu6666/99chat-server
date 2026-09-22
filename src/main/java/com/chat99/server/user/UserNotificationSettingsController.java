package com.chat99.server.user;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserNotificationSettingsController {

    private final UserNotificationSettingsService settingsService;

    public UserNotificationSettingsController(UserNotificationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/me/notification-settings")
    public UserNotificationSettingsService.NotificationSettingsView getMine(Authentication auth) {
        return settingsService.getForUser((String) auth.getPrincipal());
    }

    @PutMapping("/me/notification-settings")
    @Transactional
    public UserNotificationSettingsService.NotificationSettingsView updateMine(
        Authentication auth,
        @Valid @RequestBody UserNotificationSettingsService.NotificationSettingsUpdateRequest req) {
        return settingsService.updateForUser((String) auth.getPrincipal(), req);
    }
}
