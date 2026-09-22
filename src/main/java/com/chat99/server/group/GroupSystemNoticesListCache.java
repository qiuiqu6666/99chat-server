package com.chat99.server.group;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * {@code GET /me/group-notices} 短缓存：按 userId 升版本失效。
 * Redis 异常时静默降级为直读 DB。
 */
@Service
public class GroupSystemNoticesListCache {

    private static final Logger log = LoggerFactory.getLogger(GroupSystemNoticesListCache.class);
    private static final String VER_PREFIX = "group-notices:ver:";
    private static final String DATA_PREFIX = "group-notices:data:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;

    public GroupSystemNoticesListCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${chat99.group.group-notices-cache-enabled:true}") boolean enabled,
            @Value("${chat99.group.group-notices-cache-ttl-seconds:8}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1L, ttlSeconds));
    }

    public Optional<GroupSystemNoticeService.GroupSystemNoticeListResponse> get(
            String userId, int limit, int offset, long sinceMs, boolean unreadOnly) {
        if (!enabled || blank(userId)) {
            return Optional.empty();
        }
        try {
            long ver = currentVersion(userId);
            String raw = redis.opsForValue().get(dataKey(userId, ver, limit, offset, sinceMs, unreadOnly));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, new TypeReference<>() {}));
        } catch (Exception e) {
            log.debug("group-notices cache get miss/err userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String userId, int limit, int offset, long sinceMs, boolean unreadOnly,
                    GroupSystemNoticeService.GroupSystemNoticeListResponse body) {
        if (!enabled || blank(userId) || body == null) {
            return;
        }
        try {
            long ver = currentVersion(userId);
            String json = objectMapper.writeValueAsString(body);
            redis.opsForValue().set(dataKey(userId, ver, limit, offset, sinceMs, unreadOnly), json, ttl);
        } catch (Exception e) {
            log.debug("group-notices cache put err userId={}: {}", userId, e.getMessage());
        }
    }

    public void invalidateUser(String userId) {
        if (!enabled || blank(userId)) {
            return;
        }
        try {
            redis.opsForValue().increment(verKey(userId));
            redis.expire(verKey(userId), Duration.ofDays(7));
        } catch (Exception e) {
            log.debug("group-notices cache invalidate err userId={}: {}", userId, e.getMessage());
        }
    }

    private long currentVersion(String userId) {
        String raw = redis.opsForValue().get(verKey(userId));
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String verKey(String userId) {
        return VER_PREFIX + userId.trim();
    }

    private static String dataKey(String userId, long ver, int limit, int offset,
                                  long sinceMs, boolean unreadOnly) {
        return DATA_PREFIX + userId.trim() + ":v" + ver + ":" + limit + ":" + offset
            + ":" + sinceMs + ":" + unreadOnly;
    }
}
