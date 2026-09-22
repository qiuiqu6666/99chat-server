package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.chat99.server.push.UserPushToken;
import com.chat99.server.push.UserPushTokenRepository;
import com.chat99.server.security.UserSessionService;
import com.chat99.server.user.DeviceModelDisplayService;
import com.chat99.server.user.LoginLog;
import com.chat99.server.user.LoginLogRepository;
import com.chat99.server.user.PresenceService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserDevice;
import com.chat99.server.user.UserDeviceRepository;
import com.chat99.server.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDevicesService {

    private final UserDeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final LoginLogRepository loginLogRepository;
    private final UserPushTokenRepository pushTokenRepository;
    private final AdminBannedDeviceRepository bannedRepository;
    private final PresenceService presenceService;
    private final AdminAuditService auditService;
    private final UserSessionService sessionService;
    private final DeviceModelDisplayService modelDisplay;

    public AdminDevicesService(UserDeviceRepository deviceRepository,
                               UserRepository userRepository,
                               LoginLogRepository loginLogRepository,
                               UserPushTokenRepository pushTokenRepository,
                               AdminBannedDeviceRepository bannedRepository,
                               PresenceService presenceService,
                               AdminAuditService auditService,
                               UserSessionService sessionService,
                               DeviceModelDisplayService modelDisplay) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.loginLogRepository = loginLogRepository;
        this.pushTokenRepository = pushTokenRepository;
        this.bannedRepository = bannedRepository;
        this.presenceService = presenceService;
        this.auditService = auditService;
        this.sessionService = sessionService;
        this.modelDisplay = modelDisplay;
    }

    public boolean isDeviceBanned(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return bannedRepository.existsById(deviceId.trim());
    }

    public DeviceListResponse listDevices(String userUid, String deviceId, String deviceFingerprint,
                                          String keyword, String systemType, String appVersion,
                                          String loginIp, String status, int page, int pageSize) {
        int safeSize = clampPageSize(pageSize);
        int safePage = Math.max(page, 1);
        String uid = blankToNull(userUid);
        String dev = blankToNull(firstNonBlank(deviceId, deviceFingerprint));
        String kw = blankToNull(keyword);
        String platform = mapSystemTypeFilter(systemType);

        Page<UserDevice> pageResult = deviceRepository.searchDevices(
            uid, dev, platform, kw, PageRequest.of(safePage - 1, safeSize));

        Set<String> bannedIds = loadBannedIds(pageResult.getContent());
        List<DeviceItem> items = new ArrayList<>();
        for (UserDevice device : pageResult.getContent()) {
            DeviceItem item = toDeviceItem(device, bannedIds.contains(device.getDeviceId()));
            if (!matchesPostFilters(item, appVersion, loginIp, status)) {
                continue;
            }
            items.add(item);
        }

        long total = pageResult.getTotalElements();
        if (!items.isEmpty() && items.size() < pageResult.getNumberOfElements()) {
            total = items.size();
        }
        return new DeviceListResponse(items, total, safePage, safeSize);
    }

    public DeviceItem getDevice(String id) {
        UserDevice device = requireDeviceRecord(id);
        boolean banned = isDeviceBanned(device.getDeviceId());
        return toDeviceItem(device, banned);
    }

    public SameUsersResponse listSameUsers(String id, int page, int pageSize) {
        UserDevice device = resolveDeviceRecord(id);
        String deviceId = device != null ? device.getDeviceId() : id.trim();
        int safeSize = clampPageSize(pageSize);
        int safePage = Math.max(page, 1);
        long total = loginLogRepository.countDistinctUserIdsByDeviceId(deviceId);
        List<String> userIds = loginLogRepository.findDistinctUserIdsByDeviceId(
            deviceId, PageRequest.of(safePage - 1, safeSize));
        List<SameUserItem> items = buildSameUserItems(userIds);
        return new SameUsersResponse(items, total, safePage, safeSize, (long) safePage * safeSize < total);
    }

    @Transactional
    public OkDeviceActionResult banDevice(HttpServletRequest http, String adminUsername,
                                          String id, String remark) {
        UserDevice device = requireDeviceRecord(id);
        String deviceId = device.getDeviceId();
        if (!bannedRepository.existsById(deviceId)) {
            AdminBannedDevice row = new AdminBannedDevice();
            row.setDeviceId(deviceId);
            row.setBannedBy(adminUsername);
            row.setRemark(remark);
            bannedRepository.save(row);
        }
        deviceRepository.clearTrustedByDeviceId(deviceId);
        sessionService.revokeDevice(device.getUserId(), deviceId);
        auditService.log(http, adminUsername, "device.ban", device.getUserId(),
            Map.of("device_id", deviceId, "record_id", String.valueOf(device.getId())));
        return new OkDeviceActionResult(true, String.valueOf(device.getId()), deviceId);
    }

    @Transactional
    public OkDeviceActionResult unbanDevice(HttpServletRequest http, String adminUsername,
                                            String id, String remark) {
        UserDevice device = requireDeviceRecord(id);
        bannedRepository.deleteById(device.getDeviceId());
        auditService.log(http, adminUsername, "device.unban", device.getUserId(),
            Map.of("device_id", device.getDeviceId(), "remark", remark == null ? "" : remark));
        return new OkDeviceActionResult(true, String.valueOf(device.getId()), device.getDeviceId());
    }

    @Transactional
    public OkDeviceActionResult kickDevice(HttpServletRequest http, String adminUsername,
                                           String id, String remark) {
        UserDevice device = requireDeviceRecord(id);
        sessionService.revokeDevice(device.getUserId(), device.getDeviceId());
        auditService.log(http, adminUsername, "device.kick", device.getUserId(),
            Map.of("device_id", device.getDeviceId(), "remark", remark == null ? "" : remark));
        return new OkDeviceActionResult(true, String.valueOf(device.getId()), device.getDeviceId());
    }

    private List<SameUserItem> buildSameUserItems(List<String> userIds) {
        List<SameUserItem> items = new ArrayList<>();
        for (String uid : userIds) {
            userRepository.findByUserId(uid).ifPresent(u -> items.add(toSameUserItem(u)));
        }
        return items;
    }

    private SameUserItem toSameUserItem(User u) {
        Optional<LoginLog> latest = loginLogRepository.findFirstByUserIdAndSuccessTrueOrderByCreatedAtDesc(u.getUserId());
        String ip = latest.map(LoginLog::getIp).orElse(null);
        Long loginTime = latest.map(LoginLog::getCreatedAt).map(Instant::getEpochSecond).orElse(null);
        return new SameUserItem(
            u.getUserId(),
            u.getNickname(),
            stripPlus(u.getPhone()),
            u.getStatus(),
            ip,
            loginTime);
    }

    private DeviceItem toDeviceItem(UserDevice device, boolean banned) {
        User user = userRepository.findByUserId(device.getUserId()).orElse(null);
        Optional<LoginLog> latestLog = loginLogRepository.findFirstByUserIdAndDeviceIdOrderByCreatedAtDesc(
            device.getUserId(), device.getDeviceId());
        Optional<UserPushToken> push = pushTokenRepository.findByUserIdAndDeviceId(
            device.getUserId(), device.getDeviceId());

        String platform = device.getPlatform();
        String systemType = formatSystemType(platform);
        String appVersion = latestLog.map(LoginLog::getClientVersion).orElse(null);
        String loginIp = latestLog.map(LoginLog::getIp).orElse(null);
        Long lastLoginSec = epochSec(device.getLastLoginAt());
        if (lastLoginSec == null) {
            lastLoginSec = latestLog.map(LoginLog::getCreatedAt).map(Instant::getEpochSecond).orElse(null);
        }
        Long firstLoginSec = epochSec(device.getCreatedAt());
        Long lastSeenSec = push.map(UserPushToken::getLastSeenAt).map(Instant::getEpochSecond).orElse(lastLoginSec);
        String pushMasked = push.map(t -> maskToken(t.getPushToken())).orElse(maskToken(device.getDeviceId()));
        boolean online = !banned && isDeviceLikelyOnline(device, lastSeenSec);

        return new DeviceItem(
            String.valueOf(device.getId()),
            device.getUserId(),
            user != null ? user.getNickname() : null,
            device.getDeviceId(),
            device.getDeviceId(),
            modelDisplay.display(device.getPlatform(), device.getModel()),
            null,
            systemType,
            null,
            appVersion,
            pushMasked,
            firstLoginSec,
            lastLoginSec,
            lastSeenSec,
            lastSeenSec,
            loginIp,
            null,
            banned ? "banned" : online ? "online" : "normal",
            banned,
            online);
    }

    private boolean isDeviceLikelyOnline(UserDevice device, Long lastSeenSec) {
        if (!presenceService.isLikelyOnline(device.getUserId())) {
            return false;
        }
        if (lastSeenSec == null) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - lastSeenSec * 1000L;
        return ageMs <= 90_000L;
    }

    private boolean matchesPostFilters(DeviceItem item, String appVersion, String loginIp, String status) {
        if (appVersion != null && !appVersion.isBlank()) {
            String ver = item.appVersion() == null ? "" : item.appVersion();
            if (!ver.toLowerCase(Locale.ROOT).contains(appVersion.trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (loginIp != null && !loginIp.isBlank()) {
            String ip = item.lastLoginIp() == null ? "" : item.lastLoginIp();
            if (!ip.contains(loginIp.trim())) {
                return false;
            }
        }
        if (status != null && !status.isBlank()) {
            return switch (status.trim().toLowerCase(Locale.ROOT)) {
                case "banned" -> Boolean.TRUE.equals(item.isBanned());
                case "online" -> Boolean.TRUE.equals(item.isOnline());
                case "normal" -> !Boolean.TRUE.equals(item.isBanned()) && !Boolean.TRUE.equals(item.isOnline());
                default -> true;
            };
        }
        return true;
    }

    private Set<String> loadBannedIds(List<UserDevice> devices) {
        Set<String> ids = new HashSet<>();
        for (UserDevice d : devices) {
            if (bannedRepository.existsById(d.getDeviceId())) {
                ids.add(d.getDeviceId());
            }
        }
        return ids;
    }

    private UserDevice requireDeviceRecord(String id) {
        UserDevice device = resolveDeviceRecord(id);
        if (device == null) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "device_not_found", "device_not_found");
        }
        return device;
    }

    private UserDevice resolveDeviceRecord(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        if (trimmed.matches("^\\d+$")) {
            return deviceRepository.findById(Long.parseLong(trimmed)).orElse(null);
        }
        return deviceRepository.findFirstByDeviceIdOrderByLastLoginAtDesc(trimmed).orElse(null);
    }

    private static String mapSystemTypeFilter(String systemType) {
        if (systemType == null || systemType.isBlank()) {
            return null;
        }
        return switch (systemType.trim().toLowerCase(Locale.ROOT)) {
            case "android" -> "android";
            case "ios" -> "ios";
            case "web" -> "web";
            default -> systemType.trim().toLowerCase(Locale.ROOT);
        };
    }

    private static String formatSystemType(String platform) {
        if (platform == null || platform.isBlank()) {
            return null;
        }
        return switch (platform.trim().toLowerCase(Locale.ROOT)) {
            case "android" -> "Android";
            case "ios" -> "iOS";
            case "web", "windows", "macos", "linux" -> "Web";
            default -> platform;
        };
    }

    private static Long epochSec(Instant instant) {
        return instant == null ? null : instant.getEpochSecond();
    }

    private static String maskToken(String value) {
        if (value == null || value.length() < 8) {
            return value;
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private static String stripPlus(String phone) {
        if (phone == null) {
            return null;
        }
        return phone.startsWith("+") ? phone.substring(1) : phone;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    private static int clampPageSize(int pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeviceItem(
        String id,
        String userUid,
        String nickname,
        String deviceId,
        String deviceFingerprint,
        String deviceModel,
        String brand,
        String systemType,
        String systemVersion,
        String appVersion,
        String pushTokenMasked,
        Long firstLoginTime,
        Long lastLoginTime,
        Long lastSeenAt,
        Long lastHeartbeatAt,
        String lastLoginIp,
        String lastLoginRegion,
        String status,
        Boolean isBanned,
        Boolean isOnline) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeviceListResponse(List<DeviceItem> items, long total, int page, int pageSize) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SameUserItem(
        String userUid,
        String nickname,
        String phoneNum,
        Integer userStatus,
        String latestLoginIp,
        Long latestLoginTime) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SameUsersResponse(
        List<SameUserItem> items, long total, int page, int pageSize, boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OkDeviceActionResult(boolean ok, String id, String deviceId) {}
}
