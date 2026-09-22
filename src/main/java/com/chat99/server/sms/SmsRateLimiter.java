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
        SmsProperties.Rate r = props.rate();
        consume("sms:rl:phone:" + phone, r.phonePerMinute(), Duration.ofMinutes(1));
        consume("sms:rl:phone:day:" + phone, r.phonePerDay(), Duration.ofDays(1));
        if (ip != null) consume("sms:rl:ip:" + ip, r.ipPerMinute(), Duration.ofMinutes(1));
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
