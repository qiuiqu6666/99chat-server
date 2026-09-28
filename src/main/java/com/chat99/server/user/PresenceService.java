package com.chat99.server.user;

import com.chat99.server.realtime.PresenceRealtimePublisher;
import com.chat99.server.wallet.DepositScanEligibilityService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final String HB_KEY_PREFIX = "presence:hb:";
    private static final String DEVICE_HB_KEY_PREFIX = "presence:device:";
    private static final int DEVICE_ONLINE_TTL_SECONDS = 90;

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final UserPrivacyService privacyService;
    private final PresenceProperties props;
    private final PresenceRealtimePublisher presenceRealtimePublisher;
    private final DepositScanEligibilityService depositScanEligibilityService;
    private final Set<String> pendingLastActive = ConcurrentHashMap.newKeySet();

    public PresenceService(StringRedisTemplate redis, UserRepository userRepository,
                           UserPrivacyService privacyService, PresenceProperties props,
                           PresenceRealtimePublisher presenceRealtimePublisher,
                           DepositScanEligibilityService depositScanEligibilityService) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.privacyService = privacyService;
        this.props = props;
        this.presenceRealtimePublisher = presenceRealtimePublisher;
        this.depositScanEligibilityService = depositScanEligibilityService;
    }

    public void heartbeat(String userId) {
        recordActivity(userId, null);
    }

    public void deviceHeartbeat(String userId, String deviceId) {
        recordActivity(userId, deviceId);
    }

    public void recordActivity(String userId, String deviceId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        if (deviceId != null && !deviceId.isBlank()) {
            redis.opsForValue().set(
                DEVICE_HB_KEY_PREFIX + userId + ":" + deviceId.trim(),
                "1",
                Duration.ofSeconds(DEVICE_ONLINE_TTL_SECONDS));
        }
        noteHeartbeat(userId);
    }

    public boolean isDeviceOnline(String userId, String deviceId) {
        if (userId == null || userId.isBlank() || deviceId == null || deviceId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(DEVICE_HB_KEY_PREFIX + userId + ":" + deviceId.trim()));
    }

    private void noteHeartbeat(String userId) {
        String key = HB_KEY_PREFIX + userId;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(props.heartbeatThrottleSeconds()));
        if (Boolean.TRUE.equals(acquired)) {
            pendingLastActive.add(userId);
        }
    }

    public boolean isPendingLastActive(String userId) {
        return pendingLastActive.contains(userId);
    }

    public List<String> drainPendingLastActive(int limit) {
        int cap = Math.max(limit, 0);
        List<String> batch = new ArrayList<>(Math.min(cap, pendingLastActive.size()));
        Iterator<String> it = pendingLastActive.iterator();
        while (batch.size() < cap && it.hasNext()) {
            batch.add(it.next());
            it.remove();
        }
        return batch;
    }

    public void loginActive(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        redis.opsForValue().setIfAbsent(
            HB_KEY_PREFIX + userId, "1", Duration.ofSeconds(props.heartbeatThrottleSeconds()));
        Instant now = Instant.now();
        int updated = userRepository.touchLastActive(userId, now);
        if (updated == 0) {
            log.warn("touchLastActive returned 0 for userId={}", userId);
            return;
        }
        depositScanEligibilityService.ensureRegistered(userId);
        presenceRealtimePublisher.userBecameActive(userId, now);
    }

    public record LastSeenSnapshot(Map<String, Long> lastSeen, Map<String, String> lastActiveVisibility) {}

    public LastSeenSnapshot lastSeen(String viewerUserId, Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return new LastSeenSnapshot(Map.of(), Map.of());
        }
        Map<String, Long> lastSeen = new HashMap<>();
        Map<String, String> visibility = new HashMap<>();
        for (UserRepository.OnlinePresenceView v : userRepository.findOnlinePresenceByUserIds(userIds)) {
            Long epochMs = privacyService.lastActiveAtEpochMillis(v);
            if (epochMs != null) {
                lastSeen.put(v.getUserId(), epochMs);
            }
            if (v.getLastActiveVisibility() != null) {
                visibility.put(v.getUserId(), v.getLastActiveVisibility().name());
            }
        }
        return new LastSeenSnapshot(lastSeen, visibility);
    }

    public int maxBatchSize() {
        return props.maxBatchSize();
    }

    /** 最近心跳窗口内视为在线，用于跳过离线 Push。 */
    public boolean isLikelyOnline(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redis.hasKey(HB_KEY_PREFIX + userId));
    }

    /** 一批用户里，心跳键仍存在的视为在线。 */
    public Set<String> likelyOnline(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        List<String> ids = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String userId : userIds) {
            if (userId == null || userId.isBlank()) {
                continue;
            }
            String id = userId.trim();
            ids.add(id);
            keys.add(HB_KEY_PREFIX + id);
        }
        if (keys.isEmpty()) {
            return Set.of();
        }
        List<String> values = redis.opsForValue().multiGet(keys);
        Set<String> online = new HashSet<>();
        if (values == null) {
            return online;
        }
        for (int i = 0; i < ids.size() && i < values.size(); i++) {
            if (values.get(i) != null) {
                online.add(ids.get(i));
            }
        }
        return online;
    }
}
