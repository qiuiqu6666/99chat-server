package com.chat99.server.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class RedPacketClaimStateCacheTest {

    @Mock StringRedisTemplate redis;

    RedPacketClaimStateCache cache;

    @BeforeEach
    void setup() {
        cache = new RedPacketClaimStateCache(redis);
    }

    @Test
    void tryGrab_mapsLuaCodes() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(5L);
        assertThat(cache.tryGrab(1L, "u1")).isEqualTo(RedPacketClaimStateCache.GrabResult.OK);

        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(-1L);
        assertThat(cache.tryGrab(1L, "u1")).isEqualTo(RedPacketClaimStateCache.GrabResult.EMPTY);

        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(-2L);
        assertThat(cache.tryGrab(1L, "u1")).isEqualTo(RedPacketClaimStateCache.GrabResult.ALREADY);

        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(-3L);
        assertThat(cache.tryGrab(1L, "u1")).isEqualTo(RedPacketClaimStateCache.GrabResult.MISSING);
    }

    @Test
    void tryGrab_withoutRedisIsUnavailable() {
        cache = new RedPacketClaimStateCache(null);
        assertThat(cache.available()).isFalse();
        assertThat(cache.tryGrab(1L, "u1")).isEqualTo(RedPacketClaimStateCache.GrabResult.UNAVAILABLE);
    }

    @Test
    void ttlSeconds_usesExpirePlusBuffer() {
        WalletRedPacket packet = new WalletRedPacket();
        packet.setExpiresAt(Instant.now().plus(2, ChronoUnit.HOURS));
        long ttl = RedPacketClaimStateCache.ttlSeconds(packet);
        assertThat(ttl).isGreaterThan(2 * 3600L);
        assertThat(ttl).isLessThan(2 * 3600L + RedPacketClaimStateCache.EXTRA_TTL_SECONDS + 5);
    }

    @Test
    void evict_deletesGrabAndClaimedKeys() {
        cache.evict(42L);
        verify(redis).delete(List.of(
            RedPacketClaimStateCache.CLAIMED_PREFIX + 42L,
            RedPacketClaimStateCache.GRAB_PREFIX + 42L));
    }
}
