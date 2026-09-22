package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminUsersController {

    private final AdminUserManagementService users;
    private final AdminUserDetailService userDetail;
    private final AdminLoginLogsService loginLogsService;
    private final AdminUserGenerationService generationService;

    public AdminUsersController(AdminUserManagementService users,
                                AdminUserDetailService userDetail,
                                AdminLoginLogsService loginLogsService,
                                AdminUserGenerationService generationService) {
        this.users = users;
        this.userDetail = userDetail;
        this.loginLogsService = loginLogsService;
        this.generationService = generationService;
    }

    @GetMapping
    public AdminUserManagementService.UserListResponse list(
        Authentication auth,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "10") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        @RequestParam(name = "is_online", required = false) String isOnline,
        @RequestParam(defaultValue = "register_time_desc") String sort,
        @RequestParam(name = "game_privileged", required = false) String gamePrivileged,
        @RequestParam(name = "skip_device_sms", required = false) String skipDeviceSms) {
        AdminAccess.requirePermission(auth, "user.read");
        return users.listUsers(page, pageSize, keyword, status, isOnline, sort, gamePrivileged, skipDeviceSms);
    }

    @PostMapping("/login-disabled")
    public AdminUserManagementService.LoginDisabledResult loginDisabled(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody LoginDisabledRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        boolean clearToken = req.disabled()
            && (req.clearHttpToken() == null || req.clearHttpToken());
        return users.setLoginDisabled(http, admin.username(), req.userUid(), req.disabled(), clearToken);
    }

    @PostMapping("/game-privileged")
    public AdminUserManagementService.GamePrivilegedResult gamePrivileged(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody GamePrivilegedRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return users.setGamePrivileged(http, admin.username(), req.userUid(), req.gamePrivileged());
    }

    @PostMapping("/skip-device-sms")
    public AdminUserManagementService.SkipDeviceSmsResult skipDeviceSms(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody SkipDeviceSmsRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return users.setSkipDeviceSms(http, admin.username(), req.userUid(), req.enabled());
    }

    @PostMapping("/login-password")
    public AdminUserManagementService.OkUserResult loginPassword(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody LoginPasswordRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return users.resetLoginPassword(http, admin.username(), req.userUid(), req.newPassword());
    }

    @PostMapping("/fund-password")
    public AdminUserManagementService.OkUserResult fundPassword(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody FundPasswordRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return users.resetFundPassword(http, admin.username(), req.userUid(), req.newFundPassword());
    }

    @PostMapping("/nickname")
    public AdminUserManagementService.NicknameUpdateResult nickname(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody NicknameUpdateRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return users.updateNickname(http, admin.username(), req.userUid(), req.nickname());
    }

    @GetMapping("/wallet")
    public AdminUserManagementService.UserWalletSummaryResponse wallet(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid) {
        AdminAccess.requirePermission(auth, "user.read");
        return users.getWalletSummary(userUid);
    }

    @PostMapping("/wallet/balance-adjust")
    public AdminUserManagementService.BalanceAdjustResult balanceAdjust(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody BalanceAdjustRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return users.adjustBalance(http, admin.username(), req.userUid(), req.currency(), req.direction(),
            req.amount(), req.remark());
    }

    @PostMapping("/create")
    public AdminUserManagementService.CreateUserResult create(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody CreateUserRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return users.createUser(http, admin.username(), req.nickname(), req.password(), req.sex());
    }

    @PostMapping("/create-by-count")
    public AdminUserManagementService.QuickCreateResult createByCount(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody CreateByCountRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return users.createByCount(http, admin.username(), req.password(), req.count(), req.sex());
    }

    @PostMapping("/generation-tasks")
    public AdminUserGenerationService.TaskCreatedResponse createGenerationTask(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody CreateByCountRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return generationService.createTask(
            http, admin.username(), req.password(), req.count(), req.sex());
    }

    @GetMapping("/generation-tasks")
    public AdminUserGenerationService.TaskPageResponse listGenerationTasks(
        Authentication auth,
        @RequestParam(name = "task_no", required = false) String taskNo,
        @RequestParam(name = "created_by", required = false) String createdBy,
        @RequestParam(required = false) String status,
        @RequestParam(name = "created_from", required = false) Instant createdFrom,
        @RequestParam(name = "created_to", required = false) Instant createdTo,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return generationService.listTasks(
            taskNo, createdBy, status, createdFrom, createdTo, page, pageSize);
    }

    @GetMapping("/generation-tasks/{task_no}")
    public AdminUserGenerationService.TaskDetailResponse getGenerationTask(
        Authentication auth,
        @PathVariable("task_no") String taskNo) {
        AdminAccess.requirePermission(auth, "user.read");
        return generationService.getTask(taskNo);
    }

    @GetMapping("/detail")
    public AdminUserDetailService.UserDetailResponse detail(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid) {
        AdminAccess.requirePermission(auth, "user.read");
        return userDetail.getDetail(userUid);
    }

    /** 好友 + 群列表一次返回（详情页 Tab 用） */
    @GetMapping("/social")
    public SocialListsResponse social(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return new SocialListsResponse(
            userDetail.listFriends(userUid, page, pageSize),
            userDetail.listGroups(userUid, page, pageSize));
    }

    @GetMapping("/friends")
    public AdminUserDetailService.PagedFriendsResponse friends(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return userDetail.listFriends(userUid, page, pageSize);
    }

    @GetMapping("/groups")
    public AdminUserDetailService.PagedGroupsResponse groups(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return userDetail.listGroups(userUid, page, pageSize);
    }

    @GetMapping("/login-logs")
    public AdminLoginLogsService.LoginLogsResponse loginLogs(
        Authentication auth,
        @RequestParam(name = "user_uid", required = false) String userUid,
        @RequestParam(name = "login_ip", required = false) String loginIp,
        @RequestParam(name = "device_type", required = false) Integer deviceType,
        @RequestParam(name = "login_time_from", required = false) String loginTimeFrom,
        @RequestParam(name = "login_time_to", required = false) String loginTimeTo,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "30") int pageSize,
        @RequestParam(defaultValue = "login_time_desc") String sort) {
        AdminAccess.requirePermission(auth, "user.read");
        return loginLogsService.list(
            userUid, loginIp, deviceType, loginTimeFrom, loginTimeTo, page, pageSize, sort);
    }

    @GetMapping("/related-by-ip")
    public AdminUserDetailService.PagedRelatedResponse relatedByIp(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return userDetail.listRelatedByIp(userUid, page, pageSize);
    }

    @GetMapping("/related-by-device")
    public AdminUserDetailService.PagedRelatedResponse relatedByDevice(
        Authentication auth,
        @RequestParam(name = "user_uid") @NotBlank String userUid,
        @RequestParam(name = "device_id") @NotBlank String deviceId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return userDetail.listRelatedByDevice(userUid, deviceId, page, pageSize);
    }

    @PostMapping("/login-unfreeze")
    public AdminUserDetailService.OkUnfreezeResult loginUnfreeze(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody LoginUnfreezeRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        return userDetail.loginUnfreeze(http, admin.username(), req.userUid(), req.loginKey());
    }

    @PostMapping("/create-batch")
    public AdminUserManagementService.BatchCreateResult createBatch(
        Authentication auth,
        HttpServletRequest http,
        @Valid @RequestBody BatchCreateRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        List<AdminUserManagementService.BatchCreateItem> items = req.users().stream()
            .map(u -> new AdminUserManagementService.BatchCreateItem(u.nickname(), u.sex()))
            .toList();
        return users.createBatch(http, admin.username(), req.password(), items);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginDisabledRequest(
        @NotBlank String userUid,
        @NotNull Boolean disabled,
        Boolean clearHttpToken) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GamePrivilegedRequest(
        @NotBlank String userUid,
        @NotNull Boolean gamePrivileged) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SkipDeviceSmsRequest(
        @NotBlank String userUid,
        @NotNull Boolean enabled) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginPasswordRequest(
        @NotBlank String userUid,
        @NotBlank @Size(min = 6, max = 128) String newPassword) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FundPasswordRequest(
        @NotBlank String userUid,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits") String newFundPassword) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NicknameUpdateRequest(
        @NotBlank String userUid,
        @NotBlank @Size(min = 1, max = 32) String nickname) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BalanceAdjustRequest(
        @NotBlank String userUid,
        @NotBlank String currency,
        @NotBlank String direction,
        @NotBlank String amount,
        String remark) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateByCountRequest(
        @NotBlank @Size(min = 6, max = 128) String password,
        @Min(1) @Max(100) int count,
        String sex) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CreateUserRequest(
        @NotBlank String nickname,
        @NotBlank @Size(min = 6, max = 128) String password,
        String sex) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchCreateRequest(
        @NotBlank @Size(min = 6, max = 128) String password,
        @NotEmpty @Valid List<BatchUserItem> users) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BatchUserItem(
        @NotBlank String nickname,
        String sex) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginUnfreezeRequest(String userUid, String loginKey) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SocialListsResponse(
        AdminUserDetailService.PagedFriendsResponse friends,
        AdminUserDetailService.PagedGroupsResponse groups) {}
}
