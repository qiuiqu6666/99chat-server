package com.chat99.server.user;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SearchRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SearchRateLimiter.class);

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final long missWindowMs;
    private final int missThreshold;
    private final long blockSeconds;

    public SearchRateLimiter(StringRedisTemplate redis,
                             @Value("${chat99.search.rate-limit-enabled:true}") boolean enabled,
                             @Value("${chat99.search.miss-window-minutes:30}") int missWindowMinutes,
                             @Value("${chat99.search.miss-threshold:10}") int missThreshold,
                             @Value("${chat99.search.block-hours:24}") int blockHours) {
        this.redis = redis;
        this.enabled = enabled;
        this.missWindowMs = Duration.ofMinutes(missWindowMinutes).toMillis();
        this.missThreshold = missThreshold;
        this.blockSeconds = Duration.ofHours(blockHours).toSeconds();
        if (!enabled) {
            log.warn("user search rate limit is DISABLED (chat99.search.rate-limit-enabled=false)");
        }
    }

    public void checkNotBlocked(String userId) {
        if (!enabled) {
            return;
        }
        String key = blockKey(userId);
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            throw new SearchBlockedException(ttl);
        }
    }

    public void recordMiss(String userId) {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = missKey(userId);
        redis.opsForZSet().add(key, UUID.randomUUID().toString(), (double) now);
        redis.opsForZSet().removeRangeByScore(key, 0, now - missWindowMs);
        redis.expire(key, missWindowMs, TimeUnit.MILLISECONDS);
        Long count = redis.opsForZSet().zCard(key);
        if (count != null && count >= missThreshold) {
            redis.opsForValue().set(blockKey(userId), "1", blockSeconds, TimeUnit.SECONDS);
            redis.delete(key);
            log.info("search blocked userId={} count={} blockSeconds={}", userId, count, blockSeconds);
        }
    }

    private String missKey(String userId) {
        return "search:miss:" + userId;
    }

    private String blockKey(String userId) {
        return "search:block:" + userId;
    }
}
