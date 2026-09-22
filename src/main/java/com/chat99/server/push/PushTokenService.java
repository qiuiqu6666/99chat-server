package com.chat99.server.push;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PushTokenService {

    private final UserPushTokenRepository repo;

    public PushTokenService(UserPushTokenRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public UserPushToken register(String userId, String deviceId, PushPlatform platform, String pushToken) {
        return register(userId, deviceId, platform, pushToken, null);
    }

    @Transactional
    public UserPushToken register(String userId, String deviceId, PushPlatform platform, String pushToken,
                                  String voipToken) {
        if (userId == null || userId.isBlank()) {
            throw badRequest("INVALID_USER");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw badRequest("INVALID_DEVICE");
        }
        if (platform == null) {
            throw badRequest("INVALID_PLATFORM");
        }
        if (pushToken == null || pushToken.isBlank()) {
            throw badRequest("INVALID_TOKEN");
        }
        PushProvider provider = platform == PushPlatform.IOS ? PushProvider.APNS : PushProvider.JPUSH;
        String normalizedToken = pushToken.trim();
        UserPushToken row = repo.findByUserIdAndDeviceId(userId, deviceId.trim()).orElseGet(() -> {
            UserPushToken created = new UserPushToken();
            created.setUserId(userId.trim());
            created.setDeviceId(deviceId.trim());
            return created;
        });
        row.setPlatform(platform);
        row.setProvider(provider);
        row.setPushToken(normalizedToken);
        row.setEnabled(true);
        row.setApnsEnabled(true);
        row.setLastSeenAt(Instant.now());
        if (voipToken != null && !voipToken.isBlank() && platform == PushPlatform.IOS) {
            row.setVoipPushToken(voipToken.trim());
            row.setVoipEnabled(true);
        }
        return repo.save(row);
    }

    @Transactional
    public UserPushToken registerVoip(String userId, String deviceId, String voipToken) {
        if (userId == null || userId.isBlank()) {
            throw badRequest("INVALID_USER");
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw badRequest("INVALID_DEVICE");
        }
        if (voipToken == null || voipToken.isBlank()) {
            throw badRequest("INVALID_TOKEN");
        }
        UserPushToken row = repo.findByUserIdAndDeviceId(userId, deviceId.trim())
            .orElseThrow(() -> badRequest("DEVICE_NOT_REGISTERED"));
        if (row.getPlatform() != PushPlatform.IOS) {
            throw badRequest("VOIP_IOS_ONLY");
        }
        row.setVoipPushToken(voipToken.trim());
        row.setVoipEnabled(true);
        row.setLastSeenAt(Instant.now());
        return repo.save(row);
    }

    @Transactional
    public void unregister(String userId, String deviceId) {
        if (userId == null || userId.isBlank() || deviceId == null || deviceId.isBlank()) {
            return;
        }
        repo.findByUserIdAndDeviceId(userId, deviceId.trim()).ifPresent(row -> {
            row.setEnabled(false);
            row.setApnsEnabled(false);
            row.setVoipEnabled(false);
            repo.save(row);
        });
    }

    /** 禁用用户全部 push token（含未登记在 UserDevice 的孤儿 token）。 */
    @Transactional
    public void disableAllForUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        for (UserPushToken row : repo.findByUserId(userId)) {
            row.setEnabled(false);
            row.setApnsEnabled(false);
            row.setVoipEnabled(false);
            repo.save(row);
        }
    }

    /** 禁用除指定设备外全部 push token。 */
    @Transactional
    public void disableAllExcept(String userId, String exceptDeviceId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String except = exceptDeviceId == null ? "" : exceptDeviceId.trim();
        for (UserPushToken row : repo.findByUserId(userId)) {
            if (!row.getDeviceId().equals(except)) {
                row.setEnabled(false);
                row.setApnsEnabled(false);
                row.setVoipEnabled(false);
                repo.save(row);
            }
        }
    }

    @Transactional
    public void disableToken(Long id) {
        if (id == null) {
            return;
        }
        repo.findById(id).ifPresent(row -> {
            row.setApnsEnabled(false);
            repo.save(row);
        });
    }

    @Transactional
    public void clearVoipToken(Long id) {
        if (id == null) {
            return;
        }
        repo.findById(id).ifPresent(row -> {
            row.setVoipPushToken(null);
            row.setVoipEnabled(false);
            repo.save(row);
        });
    }

    private static ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }
}
