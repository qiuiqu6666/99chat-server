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
 * {@code GET /group/{groupId}/join-applications} 短缓存。
 * 按 groupId 升版本失效，覆盖该群所有管理员视角与 includeHandled 组合。
 */
@Service
public class JoinApplicationsListCache {

    private static final Logger log = LoggerFactory.getLogger(JoinApplicationsListCache.class);
    private static final String VER_PREFIX = "join-apps:ver:";
    private static final String DATA_PREFIX = "join-apps:data:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;

    public JoinApplicationsListCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${chat99.group.join-apps-cache-enabled:true}") boolean enabled,
            @Value("${chat99.group.join-apps-cache-ttl-seconds:8}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1L, ttlSeconds));
    }

    public Optional<GroupJoinService.JoinApplicationListResponse> get(
            String groupId, String viewerUserId, boolean includeHandled) {
        if (!enabled || blank(groupId) || blank(viewerUserId)) {
            return Optional.empty();
        }
        try {
            long ver = currentVersion(groupId);
            String raw = redis.opsForValue().get(dataKey(groupId, viewerUserId, includeHandled, ver));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, new TypeReference<>() {}));
        } catch (Exception e) {
            log.debug("join-apps cache get miss/err groupId={}: {}", groupId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String groupId, String viewerUserId, boolean includeHandled,
                    GroupJoinService.JoinApplicationListResponse body) {
        if (!enabled || blank(groupId) || blank(viewerUserId) || body == null) {
            return;
        }
        try {
            long ver = currentVersion(groupId);
            String json = objectMapper.writeValueAsString(body);
            redis.opsForValue().set(dataKey(groupId, viewerUserId, includeHandled, ver), json, ttl);
        } catch (Exception e) {
            log.debug("join-apps cache put err groupId={}: {}", groupId, e.getMessage());
        }
    }

    public void invalidateGroup(String groupId) {
        if (!enabled || blank(groupId)) {
            return;
        }
        try {
            redis.opsForValue().increment(verKey(groupId));
            redis.expire(verKey(groupId), Duration.ofDays(7));
        } catch (Exception e) {
            log.debug("join-apps cache invalidate err groupId={}: {}", groupId, e.getMessage());
        }
    }

    private long currentVersion(String groupId) {
        String raw = redis.opsForValue().get(verKey(groupId));
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

    private static String verKey(String groupId) {
        return VER_PREFIX + groupId.trim();
    }

    private static String dataKey(String groupId, String viewerUserId, boolean includeHandled, long ver) {
        return DATA_PREFIX + groupId.trim() + ":" + viewerUserId.trim()
            + ":" + includeHandled + ":v" + ver;
    }
}
