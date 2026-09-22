package com.chat99.server.user;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.security.UserSessionService;
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
public class PhoneChangeService {

    private static final String STAGE_AWAIT_OLD = "AWAIT_OLD";
    private static final String STAGE_OLD_VERIFIED = "OLD_VERIFIED";
    private static final String STAGE_AWAIT_NEW = "AWAIT_NEW";

    private final UserRepository userRepository;
    private final UserDeviceService deviceService;
    private final UserSessionService sessionService;
    private final SmsCodeStore codeStore;
    private final SmsDeliveryService smsDelivery;
    private final SmsVerificationService smsVerification;
    private final PhoneUtils phoneUtils;
    private final SmsRateLimiter rateLimiter;
    private final StringRedisTemplate redis;
    private final long sessionTtlSeconds;

    public PhoneChangeService(UserRepository userRepository,
                              UserDeviceService deviceService,
                              UserSessionService sessionService,
                              SmsCodeStore codeStore,
                              SmsDeliveryService smsDelivery,
                              SmsVerificationService smsVerification,
                              PhoneUtils phoneUtils,
                              SmsRateLimiter rateLimiter,
                              StringRedisTemplate redis,
                              @Value("${chat99.phone-change.session-ttl-seconds:900}") long sessionTtlSeconds) {
        this.userRepository = userRepository;
        this.deviceService = deviceService;
        this.sessionService = sessionService;
        this.codeStore = codeStore;
        this.smsDelivery = smsDelivery;
        this.smsVerification = smsVerification;
        this.phoneUtils = phoneUtils;
        this.rateLimiter = rateLimiter;
        this.redis = redis;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    public record StartResult(String changeId, String phoneMasked, long expiresIn) {}

    public record SessionResult(String changeId, long expiresIn) {}

    public record SendNewResult(String phoneMasked, long expiresIn) {}

    public record ConfirmResult(String phone, String phoneMasked) {}

    public StartResult start(String userId, String ip) {
        User u = requireActiveUser(userId);
        if (!phoneUtils.hasBoundPhone(u.getPhone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PHONE_NOT_BOUND");
        }
        String oldPhone = u.getPhone();
        rateLimiter.check(oldPhone, ip);

        String changeId = UUID.randomUUID().toString();
        saveSession(changeId, userId, oldPhone, null, STAGE_AWAIT_OLD);

        smsDelivery.sendForScene(SmsScene.CHANGE_PHONE_OLD, oldPhone, u.getPhoneCountry(), null);

        return new StartResult(changeId, phoneUtils.mask(oldPhone), sessionTtlSeconds);
    }

    public SessionResult verifyOld(String userId, String changeId, String smsCode) {
        Session s = loadSession(changeId, userId);
        if (!STAGE_AWAIT_OLD.equals(s.stage())) {
            throw sessionExpired();
        }
        if (!smsVerification.verifyAndConsume(SmsScene.CHANGE_PHONE_OLD, s.oldPhone(), countryCodeFor(s.oldPhone()), smsCode)) {
            throw new ResponseStatusException(HttpStatus.GONE, "SMS_CODE_INVALID");
        }
        saveSession(changeId, userId, s.oldPhone(), s.newPhone(), STAGE_OLD_VERIFIED);
        return new SessionResult(changeId, remainingTtl(changeId));
    }

    public SendNewResult sendNew(String userId, String changeId, String newPhoneRaw,
                                 String phoneCountry, String ip) {
        Session s = loadSession(changeId, userId);
        if (!STAGE_OLD_VERIFIED.equals(s.stage())) {
            throw sessionExpired();
        }

        PhoneUtils.Parsed parsed;
        try {
            if (newPhoneRaw.startsWith("+")) {
                parsed = phoneUtils.parseWithRegion(newPhoneRaw, null);
            } else if (newPhoneRaw.matches("^[0-9]+$")) {
                String region = (phoneCountry == null || phoneCountry.isBlank()) ? "CN" : phoneCountry;
                parsed = phoneUtils.parseWithRegion(newPhoneRaw, region);
            } else {
                throw new IllegalArgumentException("invalid phone format");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }

        if (parsed.e164().equals(s.oldPhone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SAME_PHONE");
        }
        if (userRepository.existsByPhone(parsed.e164())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PHONE_EXISTS");
        }

        rateLimiter.check(parsed.e164(), ip);

        saveSession(changeId, userId, s.oldPhone(), parsed.e164(), STAGE_AWAIT_NEW);

        smsDelivery.sendForScene(SmsScene.CHANGE_PHONE_NEW, parsed.e164(), parsed.countryCode(), null);

        return new SendNewResult(phoneUtils.mask(parsed.e164()), remainingTtl(changeId));
    }

    @Transactional
    public ConfirmResult confirm(String userId, String changeId, String smsCode, String currentDeviceId) {
        Session s = loadSession(changeId, userId);
        if (!STAGE_AWAIT_NEW.equals(s.stage()) || s.newPhone() == null || s.newPhone().isBlank()) {
            throw sessionExpired();
        }
        if (!smsVerification.verifyAndConsume(SmsScene.CHANGE_PHONE_NEW, s.newPhone(), countryCodeFor(s.newPhone()), smsCode)) {
            throw new ResponseStatusException(HttpStatus.GONE, "SMS_CODE_INVALID");
        }

        User u = requireActiveUser(userId);
        PhoneUtils.Parsed parsed;
        try {
            parsed = phoneUtils.parseWithRegion(s.newPhone(), null);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PHONE");
        }

        u.setPhone(parsed.e164());
        u.setPhoneCountry(parsed.countryCode());
        userRepository.save(u);
        deviceService.clearAllTrusted(userId);
        if (currentDeviceId != null && !currentDeviceId.isBlank()) {
            sessionService.revokeAllExcept(userId, currentDeviceId);
        } else {
            sessionService.revokeAll(userId);
        }
        deleteSession(changeId);

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

    private Session loadSession(String changeId, String userId) {
        String key = sessionKey(changeId);
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries.isEmpty()) {
            throw sessionExpired();
        }
        String owner = (String) entries.get("userId");
        if (!userId.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CHANGE_SESSION_FORBIDDEN");
        }
        return new Session(
            (String) entries.get("oldPhone"),
            (String) entries.get("newPhone"),
            (String) entries.get("stage"));
    }

    private void saveSession(String changeId, String userId, String oldPhone,
                             String newPhone, String stage) {
        String key = sessionKey(changeId);
        redis.opsForHash().put(key, "userId", userId);
        redis.opsForHash().put(key, "oldPhone", oldPhone);
        redis.opsForHash().put(key, "stage", stage);
        if (newPhone != null) {
            redis.opsForHash().put(key, "newPhone", newPhone);
        }
        redis.expire(key, Duration.ofSeconds(sessionTtlSeconds));
    }

    private void deleteSession(String changeId) {
        redis.delete(sessionKey(changeId));
    }

    private long remainingTtl(String changeId) {
        Long ttl = redis.getExpire(sessionKey(changeId), TimeUnit.SECONDS);
        return ttl == null || ttl < 0 ? sessionTtlSeconds : ttl;
    }

    private static String sessionKey(String changeId) {
        return "phone:change:" + changeId;
    }

    private static ResponseStatusException sessionExpired() {
        return new ResponseStatusException(HttpStatus.GONE, "CHANGE_SESSION_EXPIRED");
    }

    private String countryCodeFor(String phoneE164) {
        return phoneUtils.parseWithRegion(phoneE164, null).countryCode();
    }

    private record Session(String oldPhone, String newPhone, String stage) {}
}
