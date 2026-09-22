package com.chat99.server.adminapi;

import com.chat99.server.notify.NotifyFriendBackfillService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notify")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminFriendBackfillController {

    private final NotifyFriendBackfillService backfillService;

    public AdminFriendBackfillController(NotifyFriendBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    public record BackfillBody(Integer maxUsers, Boolean resetCursor) {}

    @PostMapping("/friend-backfill")
    public Map<String, Object> start(Authentication auth, @RequestBody(required = false) BackfillBody body) {
        AdminAccess.requirePermission(auth, "user.write");
        int maxUsers = body != null && body.maxUsers() != null ? body.maxUsers() : 0;
        boolean resetCursor = body != null && Boolean.TRUE.equals(body.resetCursor());
        if (!backfillService.startAsync(maxUsers, resetCursor)) {
            return Map.of("ok", false, "error", "ALREADY_RUNNING");
        }
        return Map.of("ok", true, "started", true, "max_users", maxUsers, "reset_cursor", resetCursor);
    }

    @GetMapping("/friend-backfill/status")
    public Map<String, Object> status(Authentication auth) {
        AdminAccess.requirePermission(auth, "user.write");
        NotifyFriendBackfillService.BackfillSnapshot snapshot = backfillService.lastSnapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", backfillService.isRunning());
        out.put("cursor_after_id", backfillService.cursorAfterId());
        out.put("last", snapshot);
        return out;
    }
}
