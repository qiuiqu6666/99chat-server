package com.chat99.server.group;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupJoinController {

    private final GroupJoinService joinService;

    public GroupJoinController(GroupJoinService joinService) {
        this.joinService = joinService;
    }

    public record JoinOptionsPutRequest(
        @NotNull GroupJoinOption applyJoinOption,
        @NotNull GroupJoinOption inviteJoinOption,
        boolean allowJoinByQrCode,
        boolean allowJoinByAlias) {}

    public record InviteMembersBody(
        @NotEmpty List<String> userIds,
        String message) {}

    public record JoinGroupBody(String message, GroupJoinSource joinSource) {}

    public record DeleteJoinApplicationsBody(@NotEmpty List<Long> applicationIds) {}

    @GetMapping("/group/{groupId}/join-options")
    public GroupJoinService.JoinOptionsView getJoinOptions(@PathVariable String groupId, Authentication auth) {
        return joinService.getJoinOptions(groupId, (String) auth.getPrincipal());
    }

    @PutMapping("/group/{groupId}/join-options")
    public GroupJoinService.JoinOptionsView updateJoinOptions(
        @PathVariable String groupId,
        @Valid @RequestBody JoinOptionsPutRequest req,
        Authentication auth) {
        return joinService.updateJoinOptions(
            groupId,
            (String) auth.getPrincipal(),
            new GroupJoinService.JoinOptionsUpdateRequest(
                req.applyJoinOption(),
                req.inviteJoinOption(),
                req.allowJoinByQrCode(),
                req.allowJoinByAlias()));
    }

    @GetMapping("/group/join-lookup")
    public GroupJoinService.JoinLookupView joinLookup(
        @RequestParam String keyword,
        @RequestParam String joinSource,
        Authentication auth) {
        return joinService.joinLookup(
            (String) auth.getPrincipal(),
            keyword,
            GroupJoinSource.parse(joinSource));
    }

    @PostMapping("/group/{groupId}/members")
    public GroupJoinService.InviteMembersResponse inviteMembers(
        @PathVariable String groupId,
        @Valid @RequestBody InviteMembersBody body,
        Authentication auth) {
        return joinService.inviteMembers(
            groupId,
            (String) auth.getPrincipal(),
            new GroupJoinService.InviteMembersRequest(body.userIds(), body.message()));
    }

    @PostMapping("/group/{groupId}/join")
    public GroupJoinService.JoinGroupResponse applyJoin(
        @PathVariable String groupId,
        @RequestBody(required = false) JoinGroupBody body,
        Authentication auth) {
        return joinService.applyJoin(
            groupId,
            (String) auth.getPrincipal(),
            new GroupJoinService.JoinGroupRequest(
                body == null ? null : body.message(),
                body == null ? null : body.joinSource()));
    }

    @GetMapping("/group/{groupId}/join-applications")
    public GroupJoinService.JoinApplicationListResponse listApplications(
        @PathVariable String groupId,
        @RequestParam(defaultValue = "false") boolean includeHandled,
        Authentication auth) {
        return joinService.listApplications(groupId, (String) auth.getPrincipal(), includeHandled);
    }

    @GetMapping("/group/{groupId}/pending-invitees")
    public GroupJoinService.PendingInviteesResponse listPendingInvitees(
        @PathVariable String groupId,
        Authentication auth) {
        return joinService.listPendingInvitees(groupId, (String) auth.getPrincipal());
    }

    @PostMapping("/group/{groupId}/join-applications/{applicationId}/approve")
    public GroupJoinService.HandleApplicationResponse approve(
        @PathVariable String groupId,
        @PathVariable long applicationId,
        Authentication auth) {
        return joinService.approveApplication(groupId, (String) auth.getPrincipal(), applicationId);
    }

    @PostMapping("/group/{groupId}/join-applications/{applicationId}/reject")
    public GroupJoinService.HandleApplicationResponse reject(
        @PathVariable String groupId,
        @PathVariable long applicationId,
        Authentication auth) {
        return joinService.rejectApplication(groupId, (String) auth.getPrincipal(), applicationId);
    }

    @DeleteMapping("/group/{groupId}/join-applications/{applicationId}")
    public GroupJoinService.DeleteApplicationsResponse deleteApplication(
        @PathVariable String groupId,
        @PathVariable long applicationId,
        Authentication auth) {
        return joinService.deleteApplication(groupId, (String) auth.getPrincipal(), applicationId);
    }

    @DeleteMapping("/group/{groupId}/join-applications")
    public GroupJoinService.DeleteApplicationsResponse deleteApplications(
        @PathVariable String groupId,
        @RequestBody(required = false) DeleteJoinApplicationsBody body,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "false") boolean includePending,
        Authentication auth) {
        List<Long> ids = body == null ? List.of() : body.applicationIds();
        return joinService.deleteApplications(
            groupId, (String) auth.getPrincipal(), ids, status, includePending);
    }
}
