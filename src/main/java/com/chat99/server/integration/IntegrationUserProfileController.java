package com.chat99.server.integration;

import com.chat99.server.user.GamePrivilegeService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import com.chat99.server.security.JwtService;
import com.chat99.server.security.UserSessionService;
import io.jsonwebtoken.JwtException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 外部集成：批量查询用户资料（昵称/头像），供 sangong 等下游服务使用。 */
@RestController
@RequestMapping("/integration/v1/users")
public class IntegrationUserProfileController {

    private static final int MAX_BATCH = 200;

    private final IntegrationAuthService authService;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final UserSessionService sessionService;
    private final GamePrivilegeService gamePrivilegeService;

    public IntegrationUserProfileController(IntegrationAuthService authService,
                                            UserRepository userRepository,
                                            JwtService jwtService,
                                            UserSessionService sessionService,
                                            GamePrivilegeService gamePrivilegeService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.gamePrivilegeService = gamePrivilegeService;
    }

    public record ProfileRequest(List<String> userIds) {}

    /**
     * 三公管理端鉴权：校验主服务登录 JWT、登录会话、账号状态和有效游戏特权。
     * 下游必须同时持有 Integration Token，不能只凭用户 JWT 调用。
     */
    @PostMapping("/game-privilege/check")
    public Map<String, Object> checkGamePrivilege(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.verify(headerToken, sign, requestTime);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            String token = authorization.substring(7);
            String userId = jwtService.parseUserId(token);
            if (userId == null || userId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
            }
            User user = userRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
            if (user.getStatus() != 1) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED");
            }
            var jti = jwtService.parseJti(token);
            if (jti.isPresent() && !sessionService.isSessionActive(userId, jti.get())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED");
            }
            GamePrivilegeService.GameAdminView view = gamePrivilegeService.getAdminView(userId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("userId", userId);
            // 对下游暴露有效特权：masterEnabled && game_privileged
            out.put("gamePrivileged", view.gameEnabledEffective());
            out.put("masterEnabled", view.masterEnabled());
            out.put("gameEnabledEffective", view.gameEnabledEffective());
            return out;
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    @PostMapping("/profiles")
    public Map<String, Object> profiles(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) ProfileRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (body == null || body.userIds() == null || body.userIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : body.userIds()) {
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
            if (ids.size() >= MAX_BATCH) {
                break;
            }
        }
        List<Map<String, Object>> profiles = new ArrayList<>();
        if (!ids.isEmpty()) {
            for (User user : userRepository.findByUserIdIn(ids)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userId", user.getUserId());
                row.put("nickname", user.getNickname());
                row.put("avatarUrl", user.getAvatarUrl());
                profiles.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("profiles", profiles);
        return out;
    }
}
