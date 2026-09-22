package com.chat99.server.user;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class LocationRateLimiter {

    private static final String KEY_PREFIX = "location:write:";

    private final StringRedisTemplate redis;
    private final LocationProperties props;

    public LocationRateLimiter(StringRedisTemplate redis, LocationProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /**
     * @return remaining cooldown ms if rate-limited; empty if allowed (and key marked)
     */
    public long tryAcquireOrRemainingMs(String userId) {
        String key = KEY_PREFIX + userId;
        Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(props.minIntervalSeconds()));
        if (Boolean.TRUE.equals(ok)) {
            return 0L;
        }
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        if (ttl == null || ttl < 0) {
            return props.minIntervalSeconds() * 1000L;
        }
        return Math.max(1L, ttl);
    }

    public long intervalMs() {
        return props.minIntervalSeconds() * 1000L;
    }
}
