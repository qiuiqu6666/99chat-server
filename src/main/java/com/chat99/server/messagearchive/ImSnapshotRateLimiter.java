package com.chat99.server.messagearchive;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ImSnapshotRateLimiter {

    private static final String KEY_PREFIX = "msg:snapshot:rl:";

    private final StringRedisTemplate redis;
    private final MessageArchiveProperties props;

    public ImSnapshotRateLimiter(StringRedisTemplate redis, MessageArchiveProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void check(String userId) {
        String key = KEY_PREFIX + userId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(1));
        }
        if (count != null && count > props.snapshot().rateLimitPerSecond()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        }
    }
}
