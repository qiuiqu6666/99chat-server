package com.chat99.server.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupProfileController {

    private final GroupProfileService profileService;

    public GroupProfileController(GroupProfileService profileService) {
        this.profileService = profileService;
    }

    public record GroupProfilePutBody(String groupName, String notice) {}

    public record MyNameCardPutBody(@NotBlank String nameCard) {}

    @GetMapping("/group/{groupId}")
    public GroupProfileView getGroup(@PathVariable String groupId, Authentication auth) {
        return profileService.getDetail(groupId, (String) auth.getPrincipal());
    }

    @PutMapping("/group/{groupId}")
    public GroupProfileView updateGroup(
        @PathVariable String groupId,
        @RequestBody GroupProfilePutBody body,
        Authentication auth) {
        return profileService.updateProfile(
            groupId,
            (String) auth.getPrincipal(),
            new GroupProfileService.ProfileUpdateRequest(body.groupName(), body.notice()));
    }

    @PutMapping("/group/{groupId}/members/me")
    public GroupProfileView updateMyNameCard(
        @PathVariable String groupId,
        @Valid @RequestBody MyNameCardPutBody body,
        Authentication auth) {
        return profileService.updateMyNameCard(groupId, (String) auth.getPrincipal(), body.nameCard());
    }
}
