package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 在线活跃 HTTP 接口。TCP 已连接时客户端应优先走实时通道：
 * <ul>
 *   <li>心跳：TCP {@code ping}（可选 {@code deviceId}），替代周期 {@code POST /me/heartbeat}</li>
 *   <li>批量 last-seen：TCP {@code presence_last_seen}，替代 {@code POST /presence/last-seen}</li>
 * </ul>
 * HTTP 仍保留作未连 TCP / 降级回退。
 */
@RestController
public class PresenceController {

    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    /** TCP 优先：已连实时通道时用 {@code ping}，本接口作回退。 */
    @PostMapping("/me/heartbeat")
    public Map<String, Object> heartbeat(Authentication auth,
                                         @Valid @RequestBody(required = false) HeartbeatRequest req) {
        String userId = (String) auth.getPrincipal();
        if (req != null && req.deviceId() != null && !req.deviceId().isBlank()) {
            presenceService.deviceHeartbeat(userId, req.deviceId());
        } else {
            presenceService.heartbeat(userId);
        }
        return Map.of("ok", true);
    }

    /** TCP 优先：已连实时通道时用 {@code presence_last_seen}，本接口作回退。 */
    @PostMapping("/presence/last-seen")
    public Map<String, Object> lastSeen(Authentication auth, @Valid @RequestBody LastSeenRequest req) {
        if (req.userIds().size() > presenceService.maxBatchSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }
        PresenceService.LastSeenSnapshot snapshot =
            presenceService.lastSeen((String) auth.getPrincipal(), req.userIds());
        return Map.of(
            "lastSeen", snapshot.lastSeen(),
            "lastActiveVisibility", snapshot.lastActiveVisibility());
    }

    public record LastSeenRequest(@NotEmpty List<String> userIds) {}

    public record HeartbeatRequest(@NotBlank String deviceId) {}
}
