/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.adminapi;

import com.chat99.server.adminapi.AdminApiException;
import com.chat99.server.common.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class AdminLoginRateLimiter {
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15L);
    private static final String PREFIX = "admin:login-fail:";
    private final StringRedisTemplate redis;
    private final ClientContext clientContext;

    public AdminLoginRateLimiter(StringRedisTemplate redis, ClientContext clientContext) {
        this.redis = redis;
        this.clientContext = clientContext;
    }

    public void check(HttpServletRequest request, String username) {
        if (this.count(this.ipKey(request)) >= 5L || this.count(AdminLoginRateLimiter.accountKey(username)) >= 5L) {
            throw new AdminApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "too many login attempts");
        }
    }

    public void recordFailure(HttpServletRequest request, String username) {
        this.increment(this.ipKey(request));
        this.increment(AdminLoginRateLimiter.accountKey(username));
    }

    public void clear(String username) {
        this.redis.delete(AdminLoginRateLimiter.accountKey(username));
    }

    private long count(String key) {
        String value = (String)this.redis.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        }
        catch (NumberFormatException ignored) {
            this.redis.delete(key);
            return 0L;
        }
    }

    private void increment(String key) {
        Long value = this.redis.opsForValue().increment(key);
        if (value != null && value == 1L) {
            this.redis.expire(key, WINDOW);
        }
    }

    private String ipKey(HttpServletRequest request) {
        String ip = request == null ? "unknown" : this.clientContext.ip(request);
        return "admin:login-fail:ip:" + AdminLoginRateLimiter.digest(ip == null ? "unknown" : ip);
    }

    private static String accountKey(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return "admin:login-fail:account:" + AdminLoginRateLimiter.digest(normalized);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        }
        catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
