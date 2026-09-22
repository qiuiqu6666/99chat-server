package com.chat99.server.user;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ContactMatchRateLimiter {

    private final StringRedisTemplate redis;
    private final int limitPerMinute;

    public ContactMatchRateLimiter(StringRedisTemplate redis,
                                   @Value("${chat99.contacts.match-rate-limit-per-minute:20}") int limitPerMinute) {
        this.redis = redis;
        this.limitPerMinute = limitPerMinute > 0 ? limitPerMinute : 20;
    }

    public void check(String userId) {
        String key = "contacts:match:rl:" + userId;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofMinutes(1));
        }
        if (count != null && count > limitPerMinute) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        }
    }
}
