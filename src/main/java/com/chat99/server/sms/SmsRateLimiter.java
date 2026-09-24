package com.chat99.server.sms;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class SmsRateLimiter {

    private final StringRedisTemplate redis;
    private final SmsProperties props;

    public SmsRateLimiter(StringRedisTemplate redis, SmsProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void check(String phone, String ip) {
        // 号码每分钟 / 号码每天 / IP 每分钟：按业务要求暂不限制
    }

    private void consume(String key, int limit, Duration ttl) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) redis.expire(key, ttl);
        if (n != null && n > limit) {
            Long left = redis.getExpire(key);
            throw new RateLimitedException(left != null ? left : ttl.getSeconds());
        }
    }

    public static class RateLimitedException extends RuntimeException {
        public final long retryAfterSeconds;
        public RateLimitedException(long s) { super("RATE_LIMITED"); this.retryAfterSeconds = s; }
    }
}
