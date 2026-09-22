package com.chat99.server.wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DepositHotAddressService {

    static final String KEY_PREFIX = "deposit:hot:";

    private final StringRedisTemplate redis;
    private final WalletConfigService configService;

    public DepositHotAddressService(StringRedisTemplate redis, WalletConfigService configService) {
        this.redis = redis;
        this.configService = configService;
    }

    public void markHot(String tronAddress) {
        if (tronAddress == null || tronAddress.isBlank()) {
            return;
        }
        long ttlMs = TimeUnit.MINUTES.toMillis(Math.max(configService.getDepositHotTtlMinutes(), 1));
        redis.opsForValue().set(KEY_PREFIX + tronAddress, "1", ttlMs, TimeUnit.MILLISECONDS);
    }

    public List<String> listHotAddresses() {
        List<String> out = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(200).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                out.add(key.substring(KEY_PREFIX.length()));
            }
        }
        return out;
    }
}
