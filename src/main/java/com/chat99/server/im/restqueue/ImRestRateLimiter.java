package com.chat99.server.im.restqueue;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.im.rest-queue.enabled", havingValue = "true", matchIfMissing = true)
public class ImRestRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ImRestRateLimiter.class);
    static final String KEY_PREFIX = "im:rl:";

    private final StringRedisTemplate redis;
    private final ImRestQueueProperties props;

    public ImRestRateLimiter(StringRedisTemplate redis, ImRestQueueProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** 阻塞直到拿到令牌或超过 waitMs。 */
    public boolean acquire(String apiKey, long waitMs) {
        int limit = props.rateLimit(apiKey);
        long deadline = System.currentTimeMillis() + Math.max(waitMs, 0);
        while (true) {
            try {
                String key = KEY_PREFIX + apiKey;
                Long count = redis.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redis.expire(key, Duration.ofSeconds(1));
                }
                if (count != null && count <= limit) {
                    return true;
                }
                if (count != null && count > limit) {
                    // 超限时回退计数，避免把桶打成永久超额
                    redis.opsForValue().decrement(key);
                }
            } catch (Exception e) {
                log.warn("im rest rate limiter failed api={} err={}", apiKey, e.getMessage());
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
