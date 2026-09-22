package com.chat99.server.im;

import com.chat99.server.push.PushConfigService;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ImPushDedupStore {

    private static final String KEY_PREFIX = "push:im:dedup:";

    private final StringRedisTemplate redis;
    private final PushConfigService pushConfig;

    public ImPushDedupStore(StringRedisTemplate redis, PushConfigService pushConfig) {
        this.redis = redis;
        this.pushConfig = pushConfig;
    }

    /** @return true 表示首次处理，false 表示重复回调应跳过 */
    public boolean markIfNew(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return true;
        }
        Duration ttl = Duration.ofHours(pushConfig.getDedupTtlHours());
        Boolean acquired = redis.opsForValue().setIfAbsent(KEY_PREFIX + dedupKey, "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }
}
