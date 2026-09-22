package com.chat99.server.group;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * {@code GET /me/groups} 短缓存：版本号失效，避免改群/入退群后长时间脏读。
 * Redis 异常时静默降级为直读 DB。
 */
@Service
public class MeGroupsListCache {

    private static final Logger log = LoggerFactory.getLogger(MeGroupsListCache.class);
    private static final String VER_PREFIX = "me-groups:ver:";
    private static final String DATA_PREFIX = "me-groups:data:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GroupMemberRepository memberRepository;
    private final boolean enabled;
    private final Duration ttl;

    public MeGroupsListCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            GroupMemberRepository memberRepository,
            @Value("${chat99.group.me-groups-cache-enabled:true}") boolean enabled,
            @Value("${chat99.group.me-groups-cache-ttl-seconds:10}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.memberRepository = memberRepository;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1L, ttlSeconds));
    }

    public Optional<GroupProfileService.MyGroupsResponse> get(String userId, int limit, int offset) {
        if (!enabled || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            long ver = currentVersion(userId);
            String raw = redis.opsForValue().get(dataKey(userId, ver, limit, offset));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, new TypeReference<>() {}));
        } catch (Exception e) {
            log.debug("me-groups cache get miss/err userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String userId, int limit, int offset, GroupProfileService.MyGroupsResponse body) {
        if (!enabled || userId == null || userId.isBlank() || body == null) {
            return;
        }
        try {
            long ver = currentVersion(userId);
            String json = objectMapper.writeValueAsString(body);
            redis.opsForValue().set(dataKey(userId, ver, limit, offset), json, ttl);
        } catch (Exception e) {
            log.debug("me-groups cache put err userId={}: {}", userId, e.getMessage());
        }
    }

    public void invalidateUser(String userId) {
        if (!enabled || userId == null || userId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().increment(verKey(userId));
            redis.expire(verKey(userId), Duration.ofDays(7));
        } catch (Exception e) {
            log.debug("me-groups cache invalidate user err userId={}: {}", userId, e.getMessage());
        }
    }

    public void invalidateUsers(Collection<String> userIds) {
        if (!enabled || userIds == null || userIds.isEmpty()) {
            return;
        }
        for (String userId : userIds) {
            invalidateUser(userId);
        }
    }

    /** 群资料/成员集合变化：失效该群全部本地成员的列表缓存。 */
    public void invalidateGroup(String groupId) {
        if (!enabled || groupId == null || groupId.isBlank()) {
            return;
        }
        try {
            List<String> userIds = memberRepository.findUserIdsByGroupId(groupId.trim());
            invalidateUsers(userIds);
        } catch (Exception e) {
            log.debug("me-groups cache invalidate group err groupId={}: {}", groupId, e.getMessage());
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

    private static String verKey(String userId) {
        return VER_PREFIX + userId.trim();
    }

    private static String dataKey(String userId, long ver, int limit, int offset) {
        return DATA_PREFIX + userId.trim() + ":v" + ver + ":" + offset + ":" + limit;
    }
}
