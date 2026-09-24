package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/groups")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminGroupsController {

    private final AdminGroupsService groups;

    public AdminGroupsController(AdminGroupsService groups) {
        this.groups = groups;
    }

    @GetMapping
    public AdminGroupsService.GroupListResponse list(
        Authentication auth,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(name = "g_status", required = false) String gStatus,
        @RequestParam(defaultValue = "create_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "group.read");
        return groups.listGroups(keyword, gStatus, page, pageSize, sort);
    }

    @GetMapping("/detail")
    public AdminGroupsService.GroupDetailResponse detail(
        Authentication auth,
        @RequestParam(name = "g_id") @NotBlank String groupId) {
        AdminAccess.requirePermission(auth, "group.read");
        return groups.getDetail(groupId);
    }

    @GetMapping("/members")
    public AdminGroupsService.GroupMembersResponse members(
        Authentication auth,
        @RequestParam(name = "g_id") @NotBlank String groupId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
        @RequestParam(defaultValue = "join_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "group.read");
        return groups.listMembers(groupId, page, pageSize, sort);
    }

    @GetMapping("/operation-logs")
    public AdminGroupsService.OperationLogsResponse operationLogs(
        Authentication auth,
        @RequestParam(name = "g_id") @NotBlank String groupId,
        @RequestParam(required = false) String kinds,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "30") int pageSize) {
        AdminAccess.requirePermission(auth, "group.read");
        return groups.listOperationLogs(groupId, kinds, page, pageSize);
    }

    @GetMapping("/operation-logs-all")
    public AdminGroupsService.OperationLogsResponse operationLogsAll(
        Authentication auth,
        @RequestParam(required = false) String kinds,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "30") int pageSize) {
        AdminAccess.requirePermission(auth, "group.read");
        return groups.listOperationLogsAll(kinds, page, pageSize);
    }

    @PostMapping("/game-enabled")
    public AdminGroupsService.GroupGameEnabledResult setGameEnabled(
        HttpServletRequest http,
        Authentication auth,
        @Valid @RequestBody GroupGameEnabledRequest req) {
        AdminAccess.requirePermission(auth, "group.write");
        AdminPrincipal admin = AdminAccess.require(auth);
        return groups.setGameEnabled(http, admin.username(), req.gId(), req.gameEnabled());
    }

    @PostMapping("/gameid")
    public AdminGroupsService.GroupGameidResult setGameid(
        HttpServletRequest http,
        Authentication auth,
        @Valid @RequestBody GroupGameidRequest req) {
        AdminAccess.requirePermission(auth, "group.write");
        AdminPrincipal admin = AdminAccess.require(auth);
        return groups.setGameid(http, admin.username(), req.gId(), req.gameid());
    }

    @PostMapping("/reconcile-members")
    public AdminGroupsService.GroupMembershipReconcileResult reconcileMembers(
        HttpServletRequest http,
        Authentication auth,
        @Valid @RequestBody GroupReconcileMembersRequest req) {
        AdminAccess.requirePermission(auth, "group.write");
        AdminPrincipal admin = AdminAccess.require(auth);
        return groups.reconcileMembers(http, admin.username(), req.gId());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupGameEnabledRequest(
        @NotBlank String gId,
        @NotNull Boolean gameEnabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupGameidRequest(
        @NotBlank String gId,
        String gameid) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GroupReconcileMembersRequest(
        @NotBlank String gId) {}
}
