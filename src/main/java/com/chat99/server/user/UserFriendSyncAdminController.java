package com.chat99.server.user;

import com.chat99.server.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/user-friends")
public class UserFriendSyncAdminController {

    private final AdminGuard guard;
    private final UserFriendSyncService syncService;

    public UserFriendSyncAdminController(AdminGuard guard, UserFriendSyncService syncService) {
        this.guard = guard;
        this.syncService = syncService;
    }

    public record SyncBody(Integer maxUsers, Boolean resetCursor) {}

    @PostMapping("/sync")
    public Map<String, Object> start(@RequestBody(required = false) SyncBody body, HttpServletRequest http) {
        guard.check(http);
        int maxUsers = body != null && body.maxUsers() != null ? body.maxUsers() : 0;
        boolean resetCursor = body != null && Boolean.TRUE.equals(body.resetCursor());
        if (!syncService.startAsync(maxUsers, resetCursor)) {
            return Map.of("ok", false, "error", "ALREADY_RUNNING");
        }
        return Map.of("ok", true, "started", true, "maxUsers", maxUsers, "resetCursor", resetCursor);
    }

    @PostMapping("/sync/{userId}")
    public Map<String, Object> syncOne(@PathVariable String userId, HttpServletRequest http) {
        guard.check(http);
        UserFriendSyncService.UserSyncResult result = syncService.syncUserFriends(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("result", result);
        return out;
    }

    @GetMapping("/sync/status")
    public Map<String, Object> status(HttpServletRequest http) {
        guard.check(http);
        UserFriendSyncService.SyncSnapshot snapshot = syncService.lastSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", syncService.isRunning());
        out.put("cursorAfterId", syncService.cursorAfterId());
        out.put("last", snapshot);
        return out;
    }
}
