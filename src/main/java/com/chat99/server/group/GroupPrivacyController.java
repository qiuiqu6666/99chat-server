package com.chat99.server.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupPrivacyController {

    private final GroupPrivacyService privacyService;

    public GroupPrivacyController(GroupPrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    public record GroupPrivacyUpdateRequest(@NotNull Boolean privacyProtectionEnabled) {}

    @GetMapping("/group/{groupId}/privacy")
    public GroupPrivacyService.GroupPrivacyView getGroupPrivacy(@PathVariable String groupId,
                                                                Authentication auth) {
        return privacyService.get(groupId, (String) auth.getPrincipal());
    }

    @PutMapping("/group/{groupId}/privacy")
    public GroupPrivacyService.GroupPrivacyView updateGroupPrivacy(
        @PathVariable String groupId,
        @Valid @RequestBody GroupPrivacyUpdateRequest req,
        Authentication auth) {
        return privacyService.update(groupId, (String) auth.getPrincipal(), req.privacyProtectionEnabled());
    }
}
