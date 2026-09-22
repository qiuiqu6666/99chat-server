package com.chat99.server.realtime;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RealtimeOnlineStore {

    private static final String KEY_PREFIX = "realtime:online:";

    private final StringRedisTemplate redis;
    private final RealtimeProperties props;

    public RealtimeOnlineStore(StringRedisTemplate redis, RealtimeProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public void connect(String userId, String connectionId) {
        if (userId == null || userId.isBlank() || connectionId == null || connectionId.isBlank()) {
            return;
        }
        String key = key(userId);
        redis.opsForSet().add(key, connectionId);
        redis.expire(key, ttl());
    }

    public void disconnect(String userId, String connectionId) {
        if (userId == null || userId.isBlank() || connectionId == null || connectionId.isBlank()) {
            return;
        }
        String key = key(userId);
        redis.opsForSet().remove(key, connectionId);
        Long size = redis.opsForSet().size(key);
        if (size == null || size == 0) {
            redis.delete(key);
        }
    }

    public boolean hasConnection(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        Long size = redis.opsForSet().size(key(userId));
        return size != null && size > 0;
    }

    private static String key(String userId) {
        return KEY_PREFIX + userId;
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(props.idleTimeoutSeconds() * 2L, 60L));
    }
}
