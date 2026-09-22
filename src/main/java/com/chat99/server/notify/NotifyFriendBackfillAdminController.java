package com.chat99.server.notify;

import com.chat99.server.admin.AdminGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/notify")
public class NotifyFriendBackfillAdminController {

    private final AdminGuard guard;
    private final NotifyFriendBackfillService backfillService;

    public NotifyFriendBackfillAdminController(AdminGuard guard, NotifyFriendBackfillService backfillService) {
        this.guard = guard;
        this.backfillService = backfillService;
    }

    public record BackfillBody(Integer maxUsers, Boolean resetCursor) {}

    @PostMapping("/friend-backfill")
    public Map<String, Object> start(@RequestBody(required = false) BackfillBody body, HttpServletRequest http) {
        guard.check(http);
        int maxUsers = body != null && body.maxUsers() != null ? body.maxUsers() : 0;
        boolean resetCursor = body != null && Boolean.TRUE.equals(body.resetCursor());
        if (!backfillService.startAsync(maxUsers, resetCursor)) {
            return Map.of("ok", false, "error", "ALREADY_RUNNING");
        }
        return Map.of("ok", true, "started", true, "maxUsers", maxUsers, "resetCursor", resetCursor);
    }

    @GetMapping("/friend-backfill/status")
    public Map<String, Object> status(HttpServletRequest http) {
        guard.check(http);
        NotifyFriendBackfillService.BackfillSnapshot snapshot = backfillService.lastSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", backfillService.isRunning());
        out.put("cursorAfterId", backfillService.cursorAfterId());
        out.put("last", snapshot);
        return out;
    }
}
