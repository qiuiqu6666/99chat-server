package com.chat99.server.adminapi;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.group.GroupProjectionSyncService;
import com.chat99.server.oss.OssClient;
import com.chat99.server.push.PushMessage;
import com.chat99.server.push.PushService;
import com.chat99.server.user.UserFriendSyncService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ops")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminOpsController {

    private final GroupProjectionSyncService groupProjectionSyncService;
    private final UserFriendSyncService userFriendSyncService;
    private final PushService pushService;
    private final AppSettingService settings;
    private final OssClient ossClient;
    private final ApiRequestMetricsStore apiRequestMetricsStore;

    public AdminOpsController(GroupProjectionSyncService groupProjectionSyncService,
                              UserFriendSyncService userFriendSyncService,
                              PushService pushService,
                              AppSettingService settings,
                              OssClient ossClient,
                              ApiRequestMetricsStore apiRequestMetricsStore) {
        this.groupProjectionSyncService = groupProjectionSyncService;
        this.userFriendSyncService = userFriendSyncService;
        this.pushService = pushService;
        this.settings = settings;
        this.ossClient = ossClient;
        this.apiRequestMetricsStore = apiRequestMetricsStore;
    }

    public record PushTestBody(String toUserId, String title, String body, Map<String, String> data) {}

    @GetMapping("/group-projection/status")
    public Map<String, Object> groupProjectionStatus(Authentication auth) {
        AdminAccess.requirePermission(auth, "admin.manage");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", groupProjectionSyncService.isRunning());
        out.put("last", groupProjectionSyncService.lastSnapshot());
        return out;
    }

    @GetMapping("/user-friends/sync/status")
    public Map<String, Object> friendSyncStatus(Authentication auth) {
        AdminAccess.requirePermission(auth, "admin.manage");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", userFriendSyncService.isRunning());
        out.put("last", userFriendSyncService.lastSnapshot());
        return out;
    }

    @PostMapping("/push/test")
    public Map<String, Object> pushTest(Authentication auth, @RequestBody(required = false) PushTestBody body) {
        AdminAccess.requirePermission(auth, "admin.manage");
        if (body == null || body.toUserId() == null || body.toUserId().isBlank()) {
            return Map.of("ok", false, "error", "to_user_id required", "enabled", pushService.enabled());
        }
        String title = body.title() == null || body.title().isBlank() ? "test" : body.title();
        String text = body.body() != null && !body.body().isBlank() ? body.body() : title;
        PushMessage message = PushMessage.of(title, text);
        if (body.data() != null) {
            for (Map.Entry<String, String> entry : body.data().entrySet()) {
                message = message.withData(entry.getKey(), entry.getValue());
            }
        }
        pushService.sendToUser(body.toUserId(), message);
        return Map.of("ok", true, "enabled", pushService.enabled());
    }

    @PostMapping("/settings/reload")
    public Map<String, Object> settingsReload(Authentication auth) {
        AdminAccess.requirePermission(auth, "admin.manage");
        settings.reload();
        ossClient.invalidate();
        return Map.of("ok", true, "reloaded", true);
    }

    @GetMapping("/api-metrics")
    public Map<String, Object> apiMetrics(
            Authentication auth,
            @RequestParam(name = "path_limit", defaultValue = "100") int pathLimit,
            @RequestParam(name = "slow_limit", defaultValue = "80") int slowLimit,
            @RequestParam(name = "error_limit", defaultValue = "80") int errorLimit,
            @RequestParam(name = "slow_ms", required = false) Long slowMs) {
        AdminAccess.requirePermission(auth, "admin.manage");
        if (slowMs != null) {
            apiRequestMetricsStore.setSlowThresholdMs(slowMs);
        }
        return apiRequestMetricsStore.snapshot(pathLimit, slowLimit, errorLimit);
    }

    @GetMapping("/api-metrics/requests")
    public Map<String, Object> apiMetricsRequests(
            Authentication auth,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "status_gte", required = false) Integer statusGte,
            @RequestParam(name = "ip", required = false) String ip,
            @RequestParam(name = "device", required = false) String device,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "slow_only", defaultValue = "false") boolean slowOnly,
            @RequestParam(name = "slow_ms", required = false) Long slowMs,
            @RequestParam(name = "from_ms", required = false) Long fromMs,
            @RequestParam(name = "to_ms", required = false) Long toMs) {
        AdminAccess.requirePermission(auth, "admin.manage");
        if (slowMs != null) {
            apiRequestMetricsStore.setSlowThresholdMs(slowMs);
        }
        return apiRequestMetricsStore.queryRequests(
            page,
            pageSize,
            method,
            path,
            status,
            statusGte,
            ip,
            device,
            keyword,
            slowOnly,
            fromMs,
            toMs);
    }

    @PostMapping("/api-metrics/reset")
    public Map<String, Object> apiMetricsReset(Authentication auth) {
        AdminAccess.requirePermission(auth, "admin.manage");
        apiRequestMetricsStore.reset();
        return Map.of("ok", true, "reset", true);
    }
}
