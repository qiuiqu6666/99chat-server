package com.chat99.server.user;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class UserLoginLogAppService {

    private static final int MAX_PAGE_SIZE = 50;

    private final LoginLogRepository loginLogRepository;
    private final UserDeviceRepository deviceRepository;
    private final DeviceModelDisplayService modelDisplay;

    public UserLoginLogAppService(LoginLogRepository loginLogRepository,
                                  UserDeviceRepository deviceRepository,
                                  DeviceModelDisplayService modelDisplay) {
        this.loginLogRepository = loginLogRepository;
        this.deviceRepository = deviceRepository;
        this.modelDisplay = modelDisplay;
    }

    public record LoginLogItem(
        long id,
        String loginType,
        String clientPlatform,
        String clientVersion,
        String deviceId,
        String platform,
        String model,
        String ip,
        java.time.Instant createdAt) {}

    public record LoginLogListResponse(List<LoginLogItem> items, long total, int page, int pageSize) {}

    public LoginLogListResponse listLoginLogs(String userId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<LoginLog> pageResult = loginLogRepository.findByUserIdAndSuccessTrueOrderByCreatedAtDesc(
            userId, PageRequest.of(safePage - 1, safeSize));
        List<LoginLogItem> items = new ArrayList<>();
        for (LoginLog log : pageResult.getContent()) {
            items.add(toItem(log));
        }
        return new LoginLogListResponse(
            items,
            loginLogRepository.countByUserIdAndSuccessTrue(userId),
            safePage,
            safeSize);
    }

    private LoginLogItem toItem(LoginLog log) {
        String platform = null;
        String model = null;
        if (log.getDeviceId() != null && !log.getDeviceId().isBlank() && log.getUserId() != null) {
            var device = deviceRepository.findByUserIdAndDeviceId(log.getUserId(), log.getDeviceId());
            if (device.isPresent()) {
                platform = device.get().getPlatform();
                model = device.get().getModel();
            }
        }
        if (platform == null) {
            platform = log.getClientPlatform();
        }
        return new LoginLogItem(
            log.getId(),
            log.getLoginType(),
            log.getClientPlatform(),
            log.getClientVersion(),
            log.getDeviceId(),
            platform,
            modelDisplay.display(platform != null ? platform : log.getClientPlatform(), model),
            log.getIp(),
            log.getCreatedAt());
    }
}
