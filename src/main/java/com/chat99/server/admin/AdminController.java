package com.chat99.server.admin;

import com.chat99.server.security.UserSessionService;
import com.chat99.server.user.GamePrivilegeService;
import com.chat99.server.user.LoginLog;
import com.chat99.server.user.LoginLogRepository;
import com.chat99.server.user.User;
import com.chat99.server.user.UserDeviceService;
import com.chat99.server.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AdminGuard guard;
    private final UserRepository userRepository;
    private final UserDeviceService deviceService;
    private final UserSessionService sessionService;
    private final LoginLogRepository loginLogRepository;
    private final GamePrivilegeService gamePrivilegeService;

    public AdminController(AdminGuard guard, UserRepository userRepository,
                           UserDeviceService deviceService, UserSessionService sessionService,
                           LoginLogRepository loginLogRepository,
                           GamePrivilegeService gamePrivilegeService) {
        this.guard = guard;
        this.userRepository = userRepository;
        this.deviceService = deviceService;
        this.sessionService = sessionService;
        this.loginLogRepository = loginLogRepository;
        this.gamePrivilegeService = gamePrivilegeService;
    }

    public record BypassRequest(@NotNull Boolean enabled, String reason) {}
    public record DisableRequest(@NotNull Boolean disabled, String reason) {}
    public record GamePrivilegedRequest(@NotNull Boolean gamePrivileged) {}

    @PostMapping("/users/{userId}/bypass-device")
    public Map<String, Object> setBypass(@PathVariable String userId,
                                         @RequestBody BypassRequest req,
                                         HttpServletRequest http) {
        guard.check(http);
        User u = mustFind(userId);
        u.setBypassDeviceCheck(req.enabled());
        userRepository.save(u);
        return Map.of("userId", u.getUserId(), "bypassDeviceCheck", u.isBypassDeviceCheck());
    }

    @PostMapping("/users/{userId}/game-privileged")
    public GamePrivilegeService.GameAdminView setGamePrivileged(@PathVariable String userId,
                                                                @RequestBody GamePrivilegedRequest req,
                                                                HttpServletRequest http) {
        guard.check(http);
        return gamePrivilegeService.setPrivileged(userId, req.gamePrivileged());
    }

    @PostMapping("/users/{userId}/devices/reset")
    public Map<String, Object> resetDevices(@PathVariable String userId, HttpServletRequest http) {
        guard.check(http);
        mustFind(userId);
        int cleared = deviceService.clearAllTrusted(userId);
        sessionService.revokeAll(userId);
        return Map.of("cleared", cleared);
    }

    @PostMapping("/users/{userId}/disable")
    public Map<String, Object> disable(@PathVariable String userId,
                                       @RequestBody DisableRequest req,
                                       HttpServletRequest http) {
        guard.check(http);
        User u = mustFind(userId);
        u.setStatus(req.disabled() ? 0 : 1);
        userRepository.save(u);
        if (req.disabled()) {
            sessionService.revokeAll(userId);
        }
        return Map.of("userId", u.getUserId(), "status", u.getStatus());
    }

    @GetMapping("/users/{userId}/login-logs")
    public List<LoginLog> loginLogs(@PathVariable String userId,
                                    @RequestParam(required = false) Long from,
                                    @RequestParam(required = false) Long to,
                                    @RequestParam(defaultValue = "100") int limit,
                                    HttpServletRequest http) {
        guard.check(http);
        Instant fromI = from == null ? Instant.EPOCH : Instant.ofEpochSecond(from);
        Instant toI = to == null ? Instant.now() : Instant.ofEpochSecond(to);
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return loginLogRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            userId, fromI, toI, PageRequest.of(0, safeLimit));
    }

    private User mustFind(String userId) {
        return userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }
}
