package com.chat99.server.security;

import com.chat99.server.im.ImOnlineKickService;
import com.chat99.server.push.PushTokenService;
import com.chat99.server.user.DeviceProperties;
import com.chat99.server.user.UserDevice;
import com.chat99.server.user.UserDeviceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserSessionService {

    private static final Logger log = LoggerFactory.getLogger(UserSessionService.class);

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String DEVICE_SESSIONS_KEY_PREFIX = "device:sessions:";
    /** 每用户设备索引集合（deviceId），替代 KEYS device:sessions:{uid}:* 的阻塞式全键空间扫描。 */
    static final String DEVICE_INDEX_KEY_PREFIX = "device:index:";

    private final StringRedisTemplate redis;
    private final JwtService jwtService;
    private final UserDeviceRepository deviceRepository;
    private final PushTokenService pushTokenService;
    private final DeviceProperties deviceProperties;
    private final ImOnlineKickService imOnlineKickService;

    public UserSessionService(StringRedisTemplate redis,
                              JwtService jwtService,
                              UserDeviceRepository deviceRepository,
                              PushTokenService pushTokenService,
                              DeviceProperties deviceProperties,
                              ImOnlineKickService imOnlineKickService) {
        this.redis = redis;
        this.jwtService = jwtService;
        this.deviceRepository = deviceRepository;
        this.pushTokenService = pushTokenService;
        this.deviceProperties = deviceProperties;
        this.imOnlineKickService = imOnlineKickService;
    }

    public record SessionIssueResult(String token, String jti, long expiresIn) {}

    public SessionIssueResult createSession(String userId, String deviceId, String platform) {
        String normalizedDeviceId = normalizeDeviceId(deviceId);
        clearDeviceSessions(userId, normalizedDeviceId);
        if (!normalizedDeviceId.isEmpty()) {
            enforcePlatformSessionLimit(userId, normalizedDeviceId, platform);
        }

        String jti = UUID.randomUUID().toString();
        long ttlSeconds = jwtService.expireSeconds();
        Duration ttl = Duration.ofSeconds(ttlSeconds);

        redis.opsForValue().set(sessionKey(userId, jti), normalizedDeviceId, ttl);
        String deviceSessionsKey = deviceSessionsKey(userId, normalizedDeviceId);
        redis.opsForSet().add(deviceSessionsKey, jti);
        redis.expire(deviceSessionsKey, ttl);
        indexDevice(userId, normalizedDeviceId, ttl);

        String token = jwtService.issue(userId, normalizedDeviceId, jti);
        return new SessionIssueResult(token, jti, ttlSeconds);
    }

    public boolean isSessionActive(String userId, String jti) {
        if (userId == null || userId.isBlank() || jti == null || jti.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(sessionKey(userId, jti)));
    }

    public void revokeSession(String userId, String jti) {
        if (userId == null || userId.isBlank() || jti == null || jti.isBlank()) {
            return;
        }
        String sessionKey = sessionKey(userId, jti);
        String deviceId = redis.opsForValue().get(sessionKey);
        redis.delete(sessionKey);
        if (deviceId != null && !deviceId.isBlank()) {
            redis.opsForSet().remove(deviceSessionsKey(userId, deviceId), jti);
        }
    }

    @Transactional
    public void revokeDevice(String userId, String deviceId) {
        revokeDevice(userId, deviceId, true, Set.of());
    }

    @Transactional
    public void revokeDevice(String userId, String deviceId, boolean kickImOnline) {
        revokeDevice(userId, deviceId, kickImOnline, Set.of());
    }

    @Transactional
    public void revokeDevice(String userId,
                             String deviceId,
                             boolean kickImOnline,
                             Set<String> protectDeviceIds) {
        String normalizedDeviceId = normalizeDeviceId(deviceId);
        if (kickImOnline && !normalizedDeviceId.isEmpty()) {
            Set<String> protect = protectDeviceIds == null ? Set.of() : protectDeviceIds;
            deviceRepository.findByUserIdAndDeviceId(userId, normalizedDeviceId).ifPresent(device ->
                imOnlineKickService.kickDevice(
                    userId, normalizedDeviceId, device.getPlatform(), protect));
        }
        revokeDeviceSessions(userId, normalizedDeviceId);
    }

    /**
     * 踢掉除本机外的全部设备。IM 踢人时会保护 {@code exceptDeviceId}，避免平台回退误踢本机。
     */
    @Transactional
    public int revokeAllExcept(String userId, String exceptDeviceId) {
        String normalizedExcept = normalizeDeviceId(exceptDeviceId);
        if (normalizedExcept.isEmpty()) {
            // 本机 deviceId 未知时拒绝全踢，防止把当前会话一起清掉
            return 0;
        }

        List<String> victims = new ArrayList<>();
        for (String deviceId : listKnownDeviceIds(userId)) {
            if (!sameDeviceId(deviceId, normalizedExcept)) {
                victims.add(deviceId);
            }
        }
        if (victims.isEmpty()) {
            pushTokenService.disableAllExcept(userId, normalizedExcept);
            return 0;
        }

        Map<String, UserDevice> devicesById = new HashMap<>();
        for (UserDevice device : deviceRepository.findByUserIdOrderByLastLoginAtDesc(userId)) {
            devicesById.putIfAbsent(device.getDeviceId(), device);
        }

        List<ImOnlineKickService.DeviceKickTarget> imTargets = new ArrayList<>(victims.size());
        for (String deviceId : victims) {
            UserDevice device = devicesById.get(deviceId);
            String platform = device != null ? device.getPlatform() : null;
            imTargets.add(new ImOnlineKickService.DeviceKickTarget(deviceId, platform));
        }
        imOnlineKickService.kickDevices(userId, imTargets, Set.of(normalizedExcept));

        int kicked = 0;
        for (String deviceId : victims) {
            revokeDevice(userId, deviceId, false);
            kicked++;
        }
        pushTokenService.disableAllExcept(userId, normalizedExcept);
        return kicked;
    }

    private void revokeDeviceSessions(String userId, String normalizedDeviceId) {
        Set<String> jtis = redis.opsForSet().members(deviceSessionsKey(userId, normalizedDeviceId));
        if (jtis != null) {
            for (String jti : jtis) {
                redis.delete(sessionKey(userId, jti));
            }
        }
        redis.delete(deviceSessionsKey(userId, normalizedDeviceId));
        unindexDevice(userId, normalizedDeviceId);

        deviceRepository.findByUserIdAndDeviceId(userId, normalizedDeviceId).ifPresent(device -> {
            device.setTrusted(false);
            deviceRepository.save(device);
        });
        pushTokenService.unregister(userId, normalizedDeviceId);
    }

    @Transactional
    public void revokeAll(String userId) {
        for (String deviceId : listKnownDeviceIds(userId)) {
            revokeDevice(userId, deviceId);
        }
        pushTokenService.disableAllForUser(userId);
        imOnlineKickService.invalidateLoginState(userId);
    }

    public Optional<String> deviceIdFromJti(String userId, String jti) {
        if (userId == null || userId.isBlank() || jti == null || jti.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().get(sessionKey(userId, jti)))
            .filter(value -> !value.isBlank());
    }

    public Set<String> listActiveDeviceIds(String userId) {
        if (userId == null || userId.isBlank()) {
            return Set.of();
        }
        Set<String> deviceIds = new HashSet<>();
        for (String deviceId : indexedDeviceIds(userId)) {
            if (deviceId == null || deviceId.isBlank()) {
                continue;
            }
            boolean active = false;
            Set<String> jtis = redis.opsForSet().members(deviceSessionsKey(userId, deviceId));
            if (jtis != null) {
                for (String jti : jtis) {
                    if (isSessionActive(userId, jti)) {
                        active = true;
                        break;
                    }
                }
            }
            if (active) {
                deviceIds.add(deviceId);
            } else {
                // 会话已全部过期/撤销：惰性清理索引，避免集合无限增长
                unindexDevice(userId, deviceId);
            }
        }
        return deviceIds;
    }

    private void enforcePlatformSessionLimit(String userId, String currentDeviceId, String platform) {
        int max = deviceProperties.maxActiveSessionsPerPlatform();
        if (max <= 0) {
            return;
        }

        String normalizedPlatform = normalizePlatform(platform);
        Set<String> activeDeviceIds = new HashSet<>(listActiveDeviceIds(userId));
        activeDeviceIds.remove(currentDeviceId);
        if (activeDeviceIds.isEmpty()) {
            return;
        }

        Map<String, UserDevice> devicesById = new HashMap<>();
        for (UserDevice device : deviceRepository.findByUserIdOrderByLastLoginAtDesc(userId)) {
            devicesById.putIfAbsent(device.getDeviceId(), device);
        }

        List<DeviceSessionCandidate> samePlatform = new ArrayList<>();
        for (String deviceId : activeDeviceIds) {
            UserDevice device = devicesById.get(deviceId);
            if (!normalizePlatform(device != null ? device.getPlatform() : null).equals(normalizedPlatform)) {
                continue;
            }
            samePlatform.add(new DeviceSessionCandidate(deviceId, deviceActivityTime(device)));
        }

        samePlatform.sort(Comparator.comparing(
            DeviceSessionCandidate::lastActiveAt,
            Comparator.nullsLast(Comparator.naturalOrder())));

        int toRevoke = samePlatform.size() - max + 1;
        if (toRevoke <= 0) {
            return;
        }

        List<DeviceSessionCandidate> victims = samePlatform.subList(0, toRevoke);
        List<ImOnlineKickService.DeviceKickTarget> imTargets = new ArrayList<>(victims.size());
        for (DeviceSessionCandidate victim : victims) {
            UserDevice device = devicesById.get(victim.deviceId());
            String devicePlatform = normalizePlatform(device != null ? device.getPlatform() : platform);
            imTargets.add(new ImOnlineKickService.DeviceKickTarget(victim.deviceId(), devicePlatform));
        }
        try {
            imOnlineKickService.kickDevices(userId, imTargets);
        } catch (Throwable t) {
            log.warn(
                "IM kick failed for platform session limit userId={} platform={} err={}",
                userId, normalizedPlatform, t.toString());
        }

        for (DeviceSessionCandidate oldest : victims) {
            log.info(
                "kicked earliest device for platform session limit userId={} platform={} max={} kickedDeviceId={} lastActiveAt={}",
                userId, normalizedPlatform, max, oldest.deviceId(), oldest.lastActiveAt());
            revokeDevice(userId, oldest.deviceId(), false);
        }
    }

    private record DeviceSessionCandidate(String deviceId, Instant lastActiveAt) {}

    private static Instant deviceActivityTime(UserDevice device) {
        if (device == null) {
            return Instant.EPOCH;
        }
        if (device.getLastLoginAt() != null) {
            return device.getLastLoginAt();
        }
        if (device.getCreatedAt() != null) {
            return device.getCreatedAt();
        }
        return Instant.EPOCH;
    }

    private static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "unknown";
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }

    private Set<String> listKnownDeviceIds(String userId) {
        Set<String> deviceIds = new HashSet<>();
        List<UserDevice> devices = deviceRepository.findByUserIdOrderByLastLoginAtDesc(userId);
        for (UserDevice device : devices) {
            deviceIds.add(device.getDeviceId());
        }
        deviceIds.addAll(indexedDeviceIds(userId));
        return deviceIds;
    }

    private static String normalizeDeviceId(String deviceId) {
        return deviceId == null ? "" : deviceId.trim();
    }

    private static boolean sameDeviceId(String left, String right) {
        String a = normalizeDeviceId(left);
        String b = normalizeDeviceId(right);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.equalsIgnoreCase(b);
    }

    private static String sessionKey(String userId, String jti) {
        return SESSION_KEY_PREFIX + userId + ":" + jti;
    }

    private static String deviceSessionsKey(String userId, String deviceId) {
        return DEVICE_SESSIONS_KEY_PREFIX + userId + ":" + deviceId;
    }

    private void clearDeviceSessions(String userId, String deviceId) {
        Set<String> jtis = redis.opsForSet().members(deviceSessionsKey(userId, deviceId));
        if (jtis != null) {
            for (String jti : jtis) {
                redis.delete(sessionKey(userId, jti));
            }
        }
        redis.delete(deviceSessionsKey(userId, deviceId));
        unindexDevice(userId, deviceId);
    }

    static String deviceIndexKey(String userId) {
        return DEVICE_INDEX_KEY_PREFIX + userId;
    }

    private void indexDevice(String userId, String deviceId, Duration ttl) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        String key = deviceIndexKey(userId);
        redis.opsForSet().add(key, deviceId);
        redis.expire(key, ttl);
    }

    private void unindexDevice(String userId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        redis.opsForSet().remove(deviceIndexKey(userId), deviceId);
    }

    private Set<String> indexedDeviceIds(String userId) {
        Set<String> members = redis.opsForSet().members(deviceIndexKey(userId));
        return members == null ? Set.of() : members;
    }
}
