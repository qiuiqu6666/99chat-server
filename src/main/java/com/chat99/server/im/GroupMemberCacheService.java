package com.chat99.server.im;

import com.chat99.server.push.PushConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class GroupMemberCacheService {

    private static final Logger log = LoggerFactory.getLogger(GroupMemberCacheService.class);
    private static final String KEY_PREFIX = "push:group:members:";

    private final StringRedisTemplate redis;
    private final ImAdminClient imAdmin;
    private final PushConfigService pushConfig;
    private final ObjectMapper json = new ObjectMapper();

    public GroupMemberCacheService(StringRedisTemplate redis,
                                   ImAdminClient imAdmin,
                                   PushConfigService pushConfig) {
        this.redis = redis;
        this.imAdmin = imAdmin;
        this.pushConfig = pushConfig;
    }

    public List<String> memberUserIds(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return List.of();
        }
        String key = KEY_PREFIX + groupId.trim();
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                List<String> parsed = json.readValue(cached, new TypeReference<>() {});
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.warn("group member cache read failed groupId={} err={}", groupId, e.getMessage());
        }
        int max = pushConfig.getMaxGroupMembersPerPush();
        int effectiveMax = max <= 0 ? Integer.MAX_VALUE : max;
        List<String> fresh = imAdmin.listGroupMemberUserIds(groupId, effectiveMax);
        if (fresh.isEmpty()) {
            return fresh;
        }
        try {
            Duration ttl = Duration.ofMinutes(pushConfig.getGroupMemberCacheTtlMinutes());
            redis.opsForValue().set(key, json.writeValueAsString(fresh), ttl);
        } catch (Exception e) {
            log.warn("group member cache write failed groupId={} err={}", groupId, e.getMessage());
        }
        return fresh;
    }

    public void invalidate(String groupId) {
        if (groupId != null && !groupId.isBlank()) {
            redis.delete(KEY_PREFIX + groupId.trim());
        }
    }
}
