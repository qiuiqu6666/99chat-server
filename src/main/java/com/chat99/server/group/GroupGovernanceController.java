package com.chat99.server.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupGovernanceController {

    private final GroupGovernanceService governanceService;

    public record TransferOwnerBody(@NotBlank String newOwnerUserId) {}

    public record RemoveMembersBody(@NotEmpty List<String> userIds) {}

    public record BatchMemberRolesBody(
        int role,
        @NotEmpty @Size(max = GroupGovernanceService.MAX_BATCH_MEMBER_ROLES) List<String> userIds) {}

    public GroupGovernanceController(GroupGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @PostMapping("/group/{groupId}/leave")
    public GroupGovernanceService.LeaveGroupResponse leaveGroup(@PathVariable String groupId,
                                                              Authentication auth) {
        return governanceService.leaveGroup(groupId, (String) auth.getPrincipal());
    }

    @PostMapping("/group/{groupId}/dismiss")
    public GroupGovernanceService.DismissGroupResponse dismissGroup(@PathVariable String groupId,
                                                                  Authentication auth) {
        return governanceService.dismissGroup(groupId, (String) auth.getPrincipal());
    }

    @DeleteMapping("/group/{groupId}/members")
    public GroupGovernanceService.BatchRemoveMembersResponse kickMembers(
        @PathVariable String groupId,
        @Valid @RequestBody RemoveMembersBody body,
        Authentication auth) {
        return governanceService.kickMembers(groupId, (String) auth.getPrincipal(), body.userIds());
    }

    @DeleteMapping("/group/{groupId}/members/{userId}")
    public GroupGovernanceService.RemoveMemberResponse kickMember(@PathVariable String groupId,
                                                                  @PathVariable String userId,
                                                                  Authentication auth) {
        return governanceService.kickMember(groupId, (String) auth.getPrincipal(), userId);
    }

    @PutMapping("/group/{groupId}/members/roles")
    public GroupGovernanceService.BatchMemberRoleResponse updateMemberRoles(
        @PathVariable String groupId,
        @Valid @RequestBody BatchMemberRolesBody body,
        Authentication auth) {
        return governanceService.updateMemberRoles(
            groupId, (String) auth.getPrincipal(), body.role(), body.userIds());
    }

    @PutMapping("/group/{groupId}/members/{userId}/role")
    public GroupMemberView updateMemberRole(@PathVariable String groupId,
                                            @PathVariable String userId,
                                            @Valid @RequestBody GroupGovernanceService.MemberRoleRequest body,
                                            Authentication auth) {
        return governanceService.updateMemberRole(
            groupId, (String) auth.getPrincipal(), userId, body.role());
    }

    @PostMapping("/group/{groupId}/transfer-owner")
    public GroupGovernanceService.TransferOwnerResponse transferOwner(
        @PathVariable String groupId,
        @Valid @RequestBody TransferOwnerBody body,
        Authentication auth) {
        return governanceService.transferOwner(
            groupId, (String) auth.getPrincipal(), body.newOwnerUserId());
    }

    @PutMapping("/group/{groupId}/members/{userId}/mute")
    public void muteMember(@PathVariable String groupId,
                           @PathVariable String userId,
                           @Valid @RequestBody GroupGovernanceService.MemberMuteRequest body,
                           Authentication auth) {
        governanceService.muteMember(
            groupId, (String) auth.getPrincipal(), userId, body.muteSeconds());
    }

    @PutMapping("/group/{groupId}/mute-all")
    public void muteAll(@PathVariable String groupId,
                        @Valid @RequestBody GroupGovernanceService.MuteAllRequest body,
                        Authentication auth) {
        governanceService.muteAll(groupId, (String) auth.getPrincipal(), body.shutUpAllMember());
    }
}
