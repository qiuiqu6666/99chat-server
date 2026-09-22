package com.chat99.server.auth;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class QrLoginSessionStore {

    static final String KEY_PREFIX = "auth:qr-login:";

    static final String F_STATUS = "status";
    static final String F_WEB_DEVICE_ID = "webDeviceId";
    static final String F_WEB_DEVICE_MODEL = "webDeviceModel";
    static final String F_WEB_PLATFORM = "webPlatform";
    static final String F_SCANNER_USER_ID = "scannerUserId";
    static final String F_TOKEN = "token";
    static final String F_TOKEN_EXPIRES_IN = "tokenExpiresIn";
    static final String F_USER_ID = "userId";
    static final String F_CREATED_AT = "createdAt";
    static final String F_WEB_IP = "webIp";
    static final String F_WEB_USER_AGENT = "webUserAgent";
    static final String F_WEB_CLIENT_VERSION = "webClientVersion";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SCANNED = "scanned";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_EXPIRED = "expired";

    private final StringRedisTemplate redis;

    public QrLoginSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record Session(
        String sessionId,
        String status,
        String webDeviceId,
        String webDeviceModel,
        String webPlatform,
        String scannerUserId,
        String token,
        Long tokenExpiresIn,
        String userId,
        String createdAt,
        String webIp,
        String webUserAgent,
        String webClientVersion
    ) {}

    public void create(String sessionId, String webDeviceId, String webDeviceModel,
                       String webPlatform, String webIp, String webUserAgent,
                       String webClientVersion, int ttlSeconds) {
        String key = key(sessionId);
        Map<String, String> fields = new HashMap<>();
        fields.put(F_STATUS, STATUS_PENDING);
        fields.put(F_WEB_DEVICE_ID, nullToEmpty(webDeviceId));
        fields.put(F_WEB_DEVICE_MODEL, nullToEmpty(webDeviceModel));
        fields.put(F_WEB_PLATFORM, nullToEmpty(webPlatform));
        fields.put(F_WEB_IP, nullToEmpty(webIp));
        fields.put(F_WEB_USER_AGENT, nullToEmpty(webUserAgent));
        fields.put(F_WEB_CLIENT_VERSION, nullToEmpty(webClientVersion));
        fields.put(F_CREATED_AT, String.valueOf(System.currentTimeMillis()));
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, Duration.ofSeconds(ttlSeconds));
    }

    public Optional<Session> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Map<Object, Object> entries = redis.opsForHash().entries(key(sessionId));
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromHash(sessionId, entries));
    }

    public void saveScanned(String sessionId, String scannerUserId) {
        String key = key(sessionId);
        redis.opsForHash().put(key, F_STATUS, STATUS_SCANNED);
        redis.opsForHash().put(key, F_SCANNER_USER_ID, scannerUserId);
    }

    public void saveConfirmed(String sessionId, String userId, String token, long tokenExpiresIn) {
        String key = key(sessionId);
        Map<String, String> fields = new HashMap<>();
        fields.put(F_STATUS, STATUS_CONFIRMED);
        fields.put(F_USER_ID, userId);
        fields.put(F_TOKEN, token);
        fields.put(F_TOKEN_EXPIRES_IN, String.valueOf(tokenExpiresIn));
        redis.opsForHash().putAll(key, fields);
    }

    public void saveCancelled(String sessionId) {
        redis.opsForHash().put(key(sessionId), F_STATUS, STATUS_CANCELLED);
    }

    public void markExpired(String sessionId) {
        String key = key(sessionId);
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.opsForHash().put(key, F_STATUS, STATUS_EXPIRED);
        }
    }

    private static Session fromHash(String sessionId, Map<Object, Object> entries) {
        String tokenExpiresRaw = str(entries.get(F_TOKEN_EXPIRES_IN));
        Long tokenExpiresIn = null;
        if (tokenExpiresRaw != null && !tokenExpiresRaw.isBlank()) {
            try {
                tokenExpiresIn = Long.parseLong(tokenExpiresRaw);
            } catch (NumberFormatException ignored) {
                tokenExpiresIn = null;
            }
        }
        return new Session(
            sessionId,
            str(entries.get(F_STATUS)),
            str(entries.get(F_WEB_DEVICE_ID)),
            str(entries.get(F_WEB_DEVICE_MODEL)),
            str(entries.get(F_WEB_PLATFORM)),
            str(entries.get(F_SCANNER_USER_ID)),
            str(entries.get(F_TOKEN)),
            tokenExpiresIn,
            str(entries.get(F_USER_ID)),
            str(entries.get(F_CREATED_AT)),
            str(entries.get(F_WEB_IP)),
            str(entries.get(F_WEB_USER_AGENT)),
            str(entries.get(F_WEB_CLIENT_VERSION))
        );
    }

    private static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
