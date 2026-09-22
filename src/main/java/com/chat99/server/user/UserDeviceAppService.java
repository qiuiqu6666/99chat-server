package com.chat99.server.user;

import com.chat99.server.security.UserSessionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class UserDeviceAppService {

    private static final int MAX_DEVICES = 50;

    private final UserDeviceRepository deviceRepository;
    private final LoginLogRepository loginLogRepository;
    private final PresenceService presenceService;
    private final UserSessionService sessionService;
    private final DeviceModelDisplayService modelDisplay;

    public UserDeviceAppService(UserDeviceRepository deviceRepository,
                                LoginLogRepository loginLogRepository,
                                PresenceService presenceService,
                                UserSessionService sessionService,
                                DeviceModelDisplayService modelDisplay) {
        this.deviceRepository = deviceRepository;
        this.loginLogRepository = loginLogRepository;
        this.presenceService = presenceService;
        this.sessionService = sessionService;
        this.modelDisplay = modelDisplay;
    }

    public record DeviceItem(
        String deviceId,
        String platform,
        String model,
        String appVersion,
        Instant lastLoginAt,
        String lastLoginIp,
        boolean isTrusted,
        boolean isCurrent,
        boolean isOnline) {}

    public record DeviceListResponse(List<DeviceItem> items, long total) {}

    public DeviceListResponse listDevices(String userId, String currentDeviceId) {
        Set<String> activeDeviceIds = sessionService.listActiveDeviceIds(userId);
        if (activeDeviceIds.isEmpty()) {
            return new DeviceListResponse(List.of(), 0);
        }
        List<UserDevice> devices = deviceRepository.findByUserIdOrderByLastLoginAtDesc(userId);
        List<DeviceItem> items = new ArrayList<>();
        String normalizedCurrent = normalizeDeviceId(currentDeviceId);
        for (UserDevice device : devices) {
            if (!activeDeviceIds.contains(device.getDeviceId())) {
                continue;
            }
            if (items.size() >= MAX_DEVICES) {
                break;
            }
            items.add(toItem(device, normalizedCurrent));
        }
        return new DeviceListResponse(items, items.size());
    }

    public boolean deviceBelongsToUser(String userId, String deviceId) {
        return deviceRepository.findByUserIdAndDeviceId(userId, normalizeDeviceId(deviceId)).isPresent();
    }

    private DeviceItem toItem(UserDevice device, String currentDeviceId) {
        Optional<LoginLog> latestLog = loginLogRepository.findFirstByUserIdAndDeviceIdOrderByCreatedAtDesc(
            device.getUserId(), device.getDeviceId());
        Instant lastLoginAt = device.getLastLoginAt();
        if (lastLoginAt == null) {
            lastLoginAt = latestLog.map(LoginLog::getCreatedAt).orElse(device.getCreatedAt());
        }
        return new DeviceItem(
            device.getDeviceId(),
            device.getPlatform(),
            modelDisplay.display(device.getPlatform(), device.getModel()),
            latestLog.map(LoginLog::getClientVersion).orElse(null),
            lastLoginAt,
            latestLog.map(LoginLog::getIp).orElse(null),
            device.isTrusted(),
            device.getDeviceId().equalsIgnoreCase(currentDeviceId),
            presenceService.isDeviceOnline(device.getUserId(), device.getDeviceId()));
    }

    private static String normalizeDeviceId(String deviceId) {
        return deviceId == null ? "" : deviceId.trim();
    }
}
