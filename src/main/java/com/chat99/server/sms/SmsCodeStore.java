package com.chat99.server.sms;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SmsCodeStore {

    private static final SecureRandom RNG = new SecureRandom();

    private final StringRedisTemplate redis;
    private final SmsProperties props;

    public SmsCodeStore(StringRedisTemplate redis, SmsProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public String generate() {
        int len = props.code().length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    public void store(SmsScene scene, String phone, String code) {
        redis.opsForValue().set(key(scene, phone), code, Duration.ofSeconds(props.code().ttlSeconds()));
    }

    public boolean isMasterCode(String code) {
        return props.masterCodeEnabled() && props.masterCode().equals(code);
    }

    /** 与 Redis 中验证码或万能码匹配。 */
    public boolean matchesCode(String expected, String submitted) {
        if (submitted == null || submitted.isBlank()) {
            return false;
        }
        if (isMasterCode(submitted)) {
            return true;
        }
        return expected != null && expected.equals(submitted);
    }

    public void clearIfPresent(SmsScene scene, String phone) {
        redis.delete(key(scene, phone));
    }

    public boolean verifyAndConsume(SmsScene scene, String phone, String code) {
        if (isMasterCode(code)) {
            String k = key(scene, phone);
            redis.delete(k);
            return true;
        }
        String k = key(scene, phone);
        String saved = redis.opsForValue().get(k);
        if (saved == null || !saved.equals(code)) {
            return false;
        }
        redis.delete(k);
        return true;
    }

    public String createDeviceChallenge(String phone, String userId, String deviceId) {
        String challengeId = UUID.randomUUID().toString();
        String code = generate();
        String key = deviceChallengeKey(challengeId);
        redis.opsForHash().putAll(key, Map.of(
            "phone", phone,
            "code", code,
            "userId", userId,
            "deviceId", deviceId
        ));
        redis.expire(key, Duration.ofSeconds(props.code().ttlSeconds()));
        return challengeId;
    }

    /** 同一 challenge 发码次数上限，防刷。 */
    public void checkDeviceSendLimit(String challengeId) {
        String key = "sms:device:send:" + challengeId;
        Long n = redis.opsForValue().increment(key);
        int max = props.deviceChallengeMaxSends();
        if (n != null && n == 1) {
            redis.expire(key, Duration.ofSeconds(props.code().ttlSeconds()));
        }
        if (n != null && n > max) {
            throw new DeviceSendLimitException();
        }
    }

    public DeviceChallenge readDeviceChallenge(String challengeId) {
        String key = deviceChallengeKey(challengeId);
        var entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) return null;
        return new DeviceChallenge(
            (String) entries.get("phone"),
            (String) entries.get("code"),
            (String) entries.get("userId"),
            (String) entries.get("deviceId"));
    }

    public void deleteDeviceChallenge(String challengeId) {
        redis.delete(deviceChallengeKey(challengeId));
        redis.delete("sms:device:send:" + challengeId);
    }

    private static String deviceChallengeKey(String challengeId) {
        return "sms:device:" + challengeId;
    }

    public static class DeviceSendLimitException extends RuntimeException {
        public DeviceSendLimitException() {
            super("RATE_LIMITED");
        }
    }

    private String key(SmsScene scene, String phone) {
        return switch (scene) {
            case REGISTER -> "sms:reg:" + phone;
            case LOGIN -> "sms:login:" + phone;
            case DEVICE -> "sms:device:phone:" + phone;
            case RESET -> "sms:reset:" + phone;
            case PAY_PIN_RESET -> "sms:paypin:" + phone;
            case PHONE_BIND -> "sms:bind:" + phone;
            case CHANGE_PHONE_OLD -> "sms:change_old:" + phone;
            case CHANGE_PHONE_NEW -> "sms:change_new:" + phone;
        };
    }

    public record DeviceChallenge(String phone, String code, String userId, String deviceId) {}
}
