package com.chat99.server.im.restqueue;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat99.im.rest-queue.enabled", havingValue = "true", matchIfMissing = true)
public class ImRestCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ImRestCircuitBreaker.class);
    static final String KEY_PREFIX = "im:cb:";

    private final StringRedisTemplate redis;
    private final ImRestQueueProperties props;

    public ImRestCircuitBreaker(StringRedisTemplate redis, ImRestQueueProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public boolean isOpen(String apiKey) {
        try {
            Boolean has = redis.hasKey(KEY_PREFIX + apiKey);
            return Boolean.TRUE.equals(has);
        } catch (Exception e) {
            log.warn("im rest breaker check failed api={} err={}", apiKey, e.getMessage());
            return false;
        }
    }

    public void open(String apiKey) {
        try {
            redis.opsForValue().set(
                KEY_PREFIX + apiKey,
                "1",
                Duration.ofSeconds(props.breakerCooldownSeconds()));
            log.warn("im rest circuit open api={} cooldownSec={}", apiKey, props.breakerCooldownSeconds());
        } catch (Exception e) {
            log.warn("im rest breaker open failed api={} err={}", apiKey, e.getMessage());
        }
    }
}
