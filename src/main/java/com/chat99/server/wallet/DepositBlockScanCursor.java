package com.chat99.server.wallet;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class DepositBlockScanCursor {

    private static final String KEY = "deposit:block:last_scanned";

    private final StringRedisTemplate redis;

    public DepositBlockScanCursor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long get() {
        String v = redis.opsForValue().get(KEY);
        if (v == null || v.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void set(long blockNumber) {
        redis.opsForValue().set(KEY, Long.toString(Math.max(blockNumber, 0L)));
    }
}
