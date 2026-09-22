/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.adminapi;

import com.chat99.server.adminapi.AdminJwtService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminSessionService {
    private static final String SESSION_PREFIX = "admin:session:";
    private static final String USER_SESSIONS_PREFIX = "admin:user-sessions:";
    private final StringRedisTemplate redis;
    private final AdminJwtService jwtService;

    public AdminSessionService(StringRedisTemplate redis, AdminJwtService jwtService) {
        this.redis = redis;
        this.jwtService = jwtService;
    }

    public String issue(String username, List<String> permissions) {
        String jti = UUID.randomUUID().toString();
        Duration ttl = Duration.ofSeconds(this.jwtService.expireSeconds());
        this.redis.opsForValue().set(AdminSessionService.sessionKey(jti), (Object)username, ttl);
        this.redis.opsForSet().add(AdminSessionService.userSessionsKey(username), new String[]{jti});
        this.redis.expire(AdminSessionService.userSessionsKey(username), ttl);
        return this.jwtService.issue(username, permissions, jti);
    }

    public boolean isActive(String username, String jti) {
        if (username == null || username.isBlank() || jti == null || jti.isBlank()) {
            return false;
        }
        return username.equals(this.redis.opsForValue().get(AdminSessionService.sessionKey(jti)));
    }

    public void revoke(String username, String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        this.redis.delete(AdminSessionService.sessionKey(jti));
        if (username != null && !username.isBlank()) {
            this.redis.opsForSet().remove(AdminSessionService.userSessionsKey(username), new String[]{jti});
        }
    }

    public void revokeAll(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String userKey = AdminSessionService.userSessionsKey(username);
        Set<String> jtis = this.redis.opsForSet().members(userKey);
        if (jtis != null && !jtis.isEmpty()) {
            this.redis.delete(jtis.stream().map(AdminSessionService::sessionKey).toList());
        }
        this.redis.delete(userKey);
    }

    private static String sessionKey(String jti) {
        return SESSION_PREFIX + jti;
    }

    private static String userSessionsKey(String username) {
        return USER_SESSIONS_PREFIX + username;
    }
}
