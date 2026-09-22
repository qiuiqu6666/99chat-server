package com.chat99.server.im;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 群内 IM 角色短缓存（跨实例共享）。写路径必须主动失效，避免权限延迟。
 */
@Service
public class ImGroupRoleCache {

    private static final Logger log = LoggerFactory.getLogger(ImGroupRoleCache.class);
    static final String KEY_PREFIX = "im:role:";

    private final StringRedisTemplate redis;
    private final ImProperties props;

    public ImGroupRoleCache(StringRedisTemplate redis, ImProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public boolean enabled() {
        return Boolean.TRUE.equals(props.roleCacheEnabled());
    }

    public Optional<String> get(String groupId, String userId) {
        if (!enabled() || groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(key(groupId, userId));
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (Exception e) {
            log.warn("im role cache get failed groupId={} userId={} err={}", groupId, userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String groupId, String userId, String role) {
        if (!enabled() || role == null || role.isBlank()
            || groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        try {
            int ttl = props.roleCacheTtlSeconds() == null || props.roleCacheTtlSeconds() <= 0
                ? 60 : props.roleCacheTtlSeconds();
            redis.opsForValue().set(key(groupId, userId), role, Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("im role cache put failed groupId={} userId={} err={}", groupId, userId, e.getMessage());
        }
    }

    public void evict(String groupId, String userId) {
        if (!enabled() || groupId == null || groupId.isBlank() || userId == null || userId.isBlank()) {
            return;
        }
        try {
            redis.delete(key(groupId, userId));
        } catch (Exception e) {
            log.warn("im role cache evict failed groupId={} userId={} err={}", groupId, userId, e.getMessage());
        }
    }

    public void evictAll(String groupId, Collection<String> userIds) {
        if (!enabled() || groupId == null || groupId.isBlank() || userIds == null || userIds.isEmpty()) {
            return;
        }
        for (String userId : userIds) {
            if (userId != null && !userId.isBlank()) {
                evict(groupId, userId.trim());
            }
        }
    }

    /** 解散群等场景：按前缀清理该群全部角色缓存。 */
    public void evictGroup(String groupId) {
        if (!enabled() || groupId == null || groupId.isBlank()) {
            return;
        }
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + groupId.trim() + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception e) {
            log.warn("im role cache evictGroup failed groupId={} err={}", groupId, e.getMessage());
        }
    }

    static String key(String groupId, String userId) {
        return KEY_PREFIX + groupId.trim() + ":" + userId.trim();
    }
}
