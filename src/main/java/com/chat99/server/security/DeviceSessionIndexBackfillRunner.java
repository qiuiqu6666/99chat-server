package com.chat99.server.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时用 SCAN（非阻塞）把存量 {@code device:sessions:{uid}:{deviceId}} 回填到
 * {@code device:index:{uid}} 集合，使 {@link UserSessionService} 不再依赖 KEYS。
 * 幂等、每次启动都执行（~千级 key，毫秒级），覆盖旧代码在切换窗口内新建的会话。
 */
@Component
public class DeviceSessionIndexBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeviceSessionIndexBackfillRunner.class);
    private static final String SOURCE_PREFIX = "device:sessions:";

    private final StringRedisTemplate redis;

    public DeviceSessionIndexBackfillRunner(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> keys = scanSourceKeys();
            int indexed = 0;
            for (String key : keys) {
                if (indexOne(key)) {
                    indexed++;
                }
            }
            log.info("device session index backfill scanned={} indexed={}", keys.size(), indexed);
        } catch (Exception e) {
            log.warn("device session index backfill skipped err={}", e.getMessage());
        }
    }

    private List<String> scanSourceKeys() {
        ScanOptions options = ScanOptions.scanOptions().match(SOURCE_PREFIX + "*").count(500).build();
        List<String> keys = new ArrayList<>();
        redis.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return null;
        });
        return keys;
    }

    private boolean indexOne(String key) {
        String rest = key.substring(SOURCE_PREFIX.length());
        int sep = rest.indexOf(':');
        if (sep <= 0 || sep == rest.length() - 1) {
            return false;
        }
        String userId = rest.substring(0, sep);
        String deviceId = rest.substring(sep + 1);
        if (userId.isBlank() || deviceId.isBlank()) {
            return false;
        }
        String indexKey = UserSessionService.deviceIndexKey(userId);
        redis.opsForSet().add(indexKey, deviceId);
        Long sourceTtl = redis.getExpire(key, TimeUnit.SECONDS);
        Long indexTtl = redis.getExpire(indexKey, TimeUnit.SECONDS);
        if (sourceTtl != null && sourceTtl > 0 && (indexTtl == null || indexTtl < sourceTtl)) {
            redis.expire(indexKey, sourceTtl, TimeUnit.SECONDS);
        }
        return true;
    }
}
