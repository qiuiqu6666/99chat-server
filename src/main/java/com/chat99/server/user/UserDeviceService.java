package com.chat99.server.user;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserDeviceService {

    private final UserDeviceRepository repo;

    public UserDeviceService(UserDeviceRepository repo) {
        this.repo = repo;
    }

    public boolean isTrusted(String userId, String deviceId) {
        return repo.findByUserIdAndDeviceId(userId, deviceId)
            .map(UserDevice::isTrusted).orElse(false);
    }

    @Transactional
    public void trust(String userId, String deviceId, String platform) {
        trust(userId, deviceId, platform, null);
    }

    @Transactional
    public void trust(String userId, String deviceId, String platform, String model) {
        repo.clearTrustedByUserId(userId);
        UserDevice d = repo.findByUserIdAndDeviceId(userId, deviceId).orElseGet(() -> {
            UserDevice nd = new UserDevice();
            nd.setUserId(userId);
            nd.setDeviceId(deviceId);
            return nd;
        });
        d.setPlatform(platform);
        if (model != null && !model.isBlank()) {
            d.setModel(model);
        }
        d.setTrusted(true);
        d.setTrustedAt(Instant.now());
        d.setLastLoginAt(Instant.now());
        repo.save(d);
    }

    @Transactional
    public void touchLogin(String userId, String deviceId, String platform) {
        touchLogin(userId, deviceId, platform, null);
    }

    @Transactional
    public void touchLogin(String userId, String deviceId, String platform, String model) {
        UserDevice d = repo.findByUserIdAndDeviceId(userId, deviceId).orElseGet(() -> {
            UserDevice nd = new UserDevice();
            nd.setUserId(userId);
            nd.setDeviceId(deviceId);
            return nd;
        });
        if (platform != null) d.setPlatform(platform);
        if (model != null && !model.isBlank()) {
            d.setModel(model);
        }
        d.setLastLoginAt(Instant.now());
        repo.save(d);
    }

    @Transactional
    public int clearAllTrusted(String userId) {
        return repo.clearTrustedByUserId(userId);
    }
}
