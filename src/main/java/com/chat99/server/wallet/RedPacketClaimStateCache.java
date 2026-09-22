package com.chat99.server.wallet;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 抢红包资格层：无效请求挡在 Redis（原子 DECR + 已领 SET），
 * 拆包金额仍由 DB 行锁内真随机计算，保证资金不错账。
 */
@Component
public class RedPacketClaimStateCache {

    private static final Logger log = LoggerFactory.getLogger(RedPacketClaimStateCache.class);
    static final String GRAB_PREFIX = "wallet:rp:grab:";
    static final String CLAIMED_PREFIX = "wallet:rp:claimed:";
    static final long DEFAULT_TTL_SECONDS = 48L * 3600L;
    static final long EXTRA_TTL_SECONDS = 24L * 3600L;

    public enum GrabResult {
        OK,
        EMPTY,
        ALREADY,
        MISSING,
        UNAVAILABLE
    }

    private static final DefaultRedisScript<Long> GRAB_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> INIT_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();

    static {
        GRAB_SCRIPT.setResultType(Long.class);
        GRAB_SCRIPT.setScriptText("""
            if redis.call('EXISTS', KEYS[2]) == 0 then
              return -3
            end
            if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
              return -2
            end
            local added = redis.call('SADD', KEYS[1], ARGV[1])
            if added == 0 then
              return -2
            end
            local left = redis.call('DECR', KEYS[2])
            if left < 0 then
              redis.call('INCR', KEYS[2])
              redis.call('SREM', KEYS[1], ARGV[1])
              return -1
            end
            return left
            """);
        INIT_SCRIPT.setResultType(Long.class);
        INIT_SCRIPT.setScriptText("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return 0
            end
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            redis.call('DEL', KEYS[2])
            for i = 3, #ARGV do
              redis.call('SADD', KEYS[2], ARGV[i])
            end
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            return 1
            """);
        RELEASE_SCRIPT.setResultType(Long.class);
        RELEASE_SCRIPT.setScriptText("""
            redis.call('SREM', KEYS[1], ARGV[1])
            if redis.call('EXISTS', KEYS[2]) == 1 then
              redis.call('INCR', KEYS[2])
            end
            return 1
            """);
    }

    private final StringRedisTemplate redis;

    public RedPacketClaimStateCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean available() {
        return redis != null;
    }

    public GrabResult tryGrab(long packetId, String userId) {
        if (!available() || userId == null || userId.isBlank()) {
            return GrabResult.UNAVAILABLE;
        }
        try {
            Long code = redis.execute(GRAB_SCRIPT, grabKeys(packetId), userId);
            return decodeGrab(code);
        } catch (Exception e) {
            log.warn("red packet redis grab failed packetId={}: {}", packetId, e.getMessage());
            return GrabResult.UNAVAILABLE;
        }
    }

    public boolean init(long packetId, int remainingCount, long ttlSeconds, Collection<String> claimedUserIds) {
        if (!available() || remainingCount < 0) {
            return false;
        }
        long ttl = Math.max(3600L, ttlSeconds);
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(remainingCount));
        args.add(String.valueOf(ttl));
        if (claimedUserIds != null) {
            for (String uid : claimedUserIds) {
                if (uid != null && !uid.isBlank()) {
                    args.add(uid);
                }
            }
        }
        try {
            Long created = redis.execute(INIT_SCRIPT, List.of(GRAB_PREFIX + packetId, CLAIMED_PREFIX + packetId),
                (Object[]) args.toArray(String[]::new));
            return created != null && created == 1L;
        } catch (Exception e) {
            log.warn("red packet redis init failed packetId={}: {}", packetId, e.getMessage());
            return false;
        }
    }

    public void releaseGrab(long packetId, String userId) {
        if (!available() || userId == null || userId.isBlank()) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, grabKeys(packetId), userId);
        } catch (Exception e) {
            log.warn("red packet redis release failed packetId={}: {}", packetId, e.getMessage());
        }
    }

    public void evict(long packetId) {
        if (!available()) {
            return;
        }
        try {
            redis.delete(grabKeys(packetId));
        } catch (Exception e) {
            log.warn("red packet redis evict failed packetId={}: {}", packetId, e.getMessage());
        }
    }

    static long ttlSeconds(WalletRedPacket packet) {
        if (packet == null || packet.getExpiresAt() == null) {
            return DEFAULT_TTL_SECONDS;
        }
        long untilExpire = Duration.between(Instant.now(), packet.getExpiresAt()).getSeconds();
        return Math.max(3600L, untilExpire + EXTRA_TTL_SECONDS);
    }

    private static List<String> grabKeys(long packetId) {
        return List.of(CLAIMED_PREFIX + packetId, GRAB_PREFIX + packetId);
    }

    private static GrabResult decodeGrab(Long code) {
        if (code == null) {
            return GrabResult.UNAVAILABLE;
        }
        if (code >= 0) {
            return GrabResult.OK;
        }
        if (code == -1L) {
            return GrabResult.EMPTY;
        }
        if (code == -2L) {
            return GrabResult.ALREADY;
        }
        if (code == -3L) {
            return GrabResult.MISSING;
        }
        return GrabResult.UNAVAILABLE;
    }
}
