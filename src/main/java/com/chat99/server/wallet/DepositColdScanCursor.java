package com.chat99.server.wallet;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class DepositColdScanCursor {

    private static final String KEY = "deposit:cold:cursor:derivation_index";

    private final StringRedisTemplate redis;

    public DepositColdScanCursor(StringRedisTemplate redis) {
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

    public void set(long derivationIndex) {
        redis.opsForValue().set(KEY, Long.toString(Math.max(derivationIndex, 0L)));
    }

    public void reset() {
        redis.delete(KEY);
    }
}
