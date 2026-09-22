package com.chat99.server.im;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 通过 IM REST {@code admin_kick_device} 踢指定在线实例下线；
 * 通过 {@code im_open_login_svc/kick} 作废该账号历史 UserSig。
 * <p>
 * 优先用 {@code CustomIdentifier} 与业务 {@code deviceId} 精确匹配。
 * 仅当在线实例没有 CustomIdentifier 时，才允许按平台回退；
 * 避免「踢其他 Android 设备」时，因对不上 CustomIdentifier 而误踢本机同平台实例。
 */
@Service
public class ImOnlineKickService {

    private static final Logger log = LoggerFactory.getLogger(ImOnlineKickService.class);

    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;

    public ImOnlineKickService(ImAdminClient imAdmin, ImUserIdService imUserIdService) {
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
    }

    public record DeviceKickTarget(String deviceId, String platform) {}

    public void kickDevices(String userId, Collection<DeviceKickTarget> targets) {
        kickDevices(userId, targets, Set.of());
    }

    /**
     * @param protectDeviceIds 受保护的业务 deviceId（如当前本机），绝不会被踢下线
     */
    public void kickDevices(String userId,
                            Collection<DeviceKickTarget> targets,
                            Collection<String> protectDeviceIds) {
        if (userId == null || userId.isBlank() || targets == null || targets.isEmpty()) {
            return;
        }
        try {
            kickDevicesUnchecked(userId, targets, protectDeviceIds);
        } catch (Throwable t) {
            // 登录发 session 依赖踢旧设备；IM class/REST 失败不得变成 500
            log.warn("IM kick failed userId={} targets={} err={}", userId, targets.size(), t.toString());
        }
    }

    private void kickDevicesUnchecked(String userId,
                                      Collection<DeviceKickTarget> targets,
                                      Collection<String> protectDeviceIds) {
        Set<String> protectedIds = normalizeProtectIds(protectDeviceIds);
        String imUserId = imUserIdService.toIm(userId);
        List<ImAdminClient.OnlineInstance> online = imAdmin.queryOnlineInstances(imUserId);
        if (online.isEmpty()) {
            log.debug("skip IM kick, no online instances userId={}", userId);
            return;
        }

        Set<Long> assigned = new HashSet<>();
        // 先占住受保护实例，避免后续平台回退抢到本机
        for (ImAdminClient.OnlineInstance instance : online) {
            String cid = normalizeIdentifier(instance.customIdentifier());
            if (!cid.isEmpty() && containsIgnoreCase(protectedIds, cid)) {
                assigned.add(instance.instId());
            }
        }

        List<Long> instIds = new ArrayList<>();
        for (DeviceKickTarget target : targets) {
            if (target == null || target.deviceId() == null || target.deviceId().isBlank()) {
                continue;
            }
            if (containsIgnoreCase(protectedIds, target.deviceId().trim())) {
                continue;
            }
            resolveInstance(online, target, assigned, protectedIds).ifPresent(instId -> {
                assigned.add(instId);
                instIds.add(instId);
            });
        }

        if (instIds.isEmpty()) {
            log.info("skip IM kick, no inst matched userId={} targets={}", userId, targets.size());
            return;
        }

        imAdmin.adminKickDevices(imUserId, instIds);
        log.info("IM admin_kick_device userId={} instIds={}", userId, instIds);
    }

    /**
     * 作废该账号已签发的全部 UserSig，并踢掉所有 IM 在线连接。
     * 之后客户端必须重新向业务侧申请新 UserSig 才能再登录 IM。
     */
    public void invalidateLoginState(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            String imUserId = imUserIdService.toIm(userId);
            imAdmin.kickAccount(imUserId);
            log.info("IM login_svc/kick userId={}", userId);
        } catch (Throwable t) {
            log.warn("IM login_svc/kick failed userId={} err={}", userId, t.toString());
        }
    }

    public void kickDevice(String userId, String deviceId, String platform) {
        kickDevice(userId, deviceId, platform, Set.of());
    }

    public void kickDevice(String userId, String deviceId, String platform, Collection<String> protectDeviceIds) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        kickDevices(userId, List.of(new DeviceKickTarget(deviceId, platform)), protectDeviceIds);
    }

    static Optional<Long> resolveInstance(List<ImAdminClient.OnlineInstance> online,
                                          DeviceKickTarget target,
                                          Set<Long> assigned) {
        return resolveInstance(online, target, assigned, Set.of());
    }

    static Optional<Long> resolveInstance(List<ImAdminClient.OnlineInstance> online,
                                          DeviceKickTarget target,
                                          Set<Long> assigned,
                                          Set<String> protectDeviceIds) {
        String deviceId = target.deviceId() == null ? "" : target.deviceId().trim();
        if (deviceId.isEmpty()) {
            return Optional.empty();
        }
        if (containsIgnoreCase(protectDeviceIds, deviceId)) {
            return Optional.empty();
        }

        for (ImAdminClient.OnlineInstance instance : online) {
            if (assigned.contains(instance.instId())) {
                continue;
            }
            String cid = normalizeIdentifier(instance.customIdentifier());
            if (cid.equalsIgnoreCase(deviceId)) {
                return Optional.of(instance.instId());
            }
        }

        // 平台回退：只打「没有 CustomIdentifier」的实例，避免误伤已标记为本机/其他设备的在线端
        for (ImAdminClient.OnlineInstance instance : online) {
            if (assigned.contains(instance.instId())) {
                continue;
            }
            String cid = normalizeIdentifier(instance.customIdentifier());
            if (!cid.isEmpty()) {
                continue;
            }
            if (platformMatches(target.platform(), instance.platform())) {
                return Optional.of(instance.instId());
            }
        }
        return Optional.empty();
    }

    private static Set<String> normalizeProtectIds(Collection<String> protectDeviceIds) {
        Set<String> out = new HashSet<>();
        if (protectDeviceIds == null) {
            return out;
        }
        for (String id : protectDeviceIds) {
            if (id == null) {
                continue;
            }
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static boolean containsIgnoreCase(Set<String> ids, String value) {
        if (ids == null || ids.isEmpty() || value == null || value.isBlank()) {
            return false;
        }
        for (String id : ids) {
            if (id != null && id.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim();
    }

    static boolean platformMatches(String clientPlatform, String imPlatform) {
        String client = normalizePlatform(clientPlatform);
        String im = normalizePlatform(imPlatform);
        if (client.equals(im)) {
            return true;
        }
        return switch (client) {
            case "ios", "iphone" -> "iphone".equals(im);
            case "android" -> "android".equals(im);
            case "web" -> "web".equals(im);
            case "windows", "pc" -> "pc".equals(im) || "windows".equals(im);
            case "ipad" -> "ipad".equals(im);
            case "mac", "macos" -> "mac".equals(im);
            default -> false;
        };
    }

    private static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "unknown";
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }
}
