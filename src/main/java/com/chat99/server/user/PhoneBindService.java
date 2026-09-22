package com.chat99.server.user;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.sms.SmsCodeStore;
import com.chat99.server.sms.SmsRateLimiter;
import com.chat99.server.sms.SmsScene;
import com.chat99.server.sms.SmsDeliveryService;
import com.chat99.server.sms.SmsVerificationService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PhoneBindService {

    private final UserRepository userRepository;
    private final SmsCodeStore codeStore;
    private final SmsDeliveryService smsDelivery;
    private final SmsVerificationService smsVerification;
    private final PhoneUtils phoneUtils;
    private final SmsRateLimiter rateLimiter;
    private final StringRedisTemplate redis;
    private final long sessionTtlSeconds;

    public PhoneBindService(UserRepository userRepository,
                            SmsCodeStore codeStore,
                            SmsDeliveryService smsDelivery,
                            SmsVerificationService smsVerification,
                            PhoneUtils phoneUtils,
                            SmsRateLimiter rateLimiter,
                            StringRedisTemplate redis,
                            @Value("${chat99.phone-bind.session-ttl-seconds:600}") long sessionTtlSeconds) {
        this.userRepository = userRepository;
        this.codeStore = codeStore;
        this.smsDelivery = smsDelivery;
        this.smsVerification = smsVerification;
        this.phoneUtils = phoneUtils;
        this.rateLimiter = rateLimiter;
        this.redis = redis;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public record StartResult(String bindId, String phoneMasked, long expiresIn) {}

    public record ConfirmResult(String phone, String phoneMasked) {}

    public StartResult start(String userId, String phoneRaw, String phoneCountry, String ip) {
        User u = requireActiveUser(userId);
        if (phoneUtils.hasBoundPhone(u.getPhone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_ALREADY_BOUND");
        }

        PhoneUtils.Parsed parsed = parsePhone(phoneRaw, phoneCountry);
        if (userRepository.existsByPhone(parsed.e164())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_EXISTS");
        }

        rateLimiter.check(parsed.e164(), ip);

        String bindId = UUID.randomUUID().toString();
        saveSession(bindId, userId, parsed.e164(), parsed.countryCode());

        smsDelivery.sendForScene(SmsScene.PHONE_BIND, parsed.e164(), parsed.countryCode(), null);

        return new StartResult(bindId, phoneUtils.mask(parsed.e164()), sessionTtlSeconds);
    }

    @Transactional
    public ConfirmResult confirm(String userId, String bindId, String smsCode) {
        Session s = loadSession(bindId, userId);
        if (!smsVerification.verifyAndConsume(SmsScene.PHONE_BIND, s.phone(), s.countryCode(), smsCode)) {
            throw new ResponseStatusException(HttpStatus.GONE, "SMS_CODE_INVALID");
        }

        User u = requireActiveUser(userId);
        if (phoneUtils.hasBoundPhone(u.getPhone())) {
            deleteSession(bindId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_ALREADY_BOUND");
        }
        if (userRepository.existsByPhone(s.phone())) {
            deleteSession(bindId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_EXISTS");
        }

        PhoneUtils.Parsed parsed = phoneUtils.parseWithRegion(s.phone(), null);
        u.setPhone(parsed.e164());
        u.setPhoneCountry(parsed.countryCode());
        userRepository.save(u);
        deleteSession(bindId);

        return new ConfirmResult(parsed.e164(), phoneUtils.mask(parsed.e164()));
    }

    private User requireActiveUser(String userId) {
        User u = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }
        return u;
    }

    private PhoneUtils.Parsed parsePhone(String phoneRaw, String phoneCountry) {
        try {
            if (phoneRaw.startsWith("+")) {
                return phoneUtils.parseWithRegion(phoneRaw, null);
            }
            if (phoneRaw.matches("^[0-9]+$")) {
                String region = (phoneCountry == null || phoneCountry.isBlank()) ? "CN" : phoneCountry;
                return phoneUtils.parseWithRegion(phoneRaw, region);
            }
            throw new IllegalArgumentException("invalid phone format");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }
    }

    private Session loadSession(String bindId, String userId) {
        String key = sessionKey(bindId);
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) {
            throw sessionExpired();
        }
        String owner = (String) entries.get("userId");
        if (!userId.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "BIND_SESSION_FORBIDDEN");
        }
        return new Session((String) entries.get("phone"), (String) entries.get("countryCode"));
    }

    private void saveSession(String bindId, String userId, String phone, String countryCode) {
        String key = sessionKey(bindId);
        redis.opsForHash().put(key, "userId", userId);
        redis.opsForHash().put(key, "phone", phone);
        redis.opsForHash().put(key, "countryCode", countryCode == null ? "" : countryCode);
        redis.expire(key, Duration.ofSeconds(sessionTtlSeconds));
    }

    private void deleteSession(String bindId) {
        redis.delete(sessionKey(bindId));
    }

    private static String sessionKey(String bindId) {
        return "phone:bind:" + bindId;
    }

    private static ResponseStatusException sessionExpired() {
        return new ResponseStatusException(HttpStatus.GONE, "BIND_SESSION_EXPIRED");
    }

    private record Session(String phone, String countryCode) {}
}
