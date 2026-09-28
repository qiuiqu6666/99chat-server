package com.chat99.server.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.realtime.PresenceRealtimePublisher;
import com.chat99.server.wallet.DepositScanEligibilityService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PresenceHeartbeatCoalesceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserPrivacyService privacyService;
    @Mock
    private PresenceRealtimePublisher publisher;
    @Mock
    private DepositScanEligibilityService depositScan;

    private PresenceService presence;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        presence = new PresenceService(
            redis, userRepository, privacyService,
            new PresenceProperties(30, 200, 5000, 200),
            publisher, depositScan);
    }

    @Test
    void heartbeatEnqueuesOnceAndDoesNotTouchDatabase() {
        when(values.setIfAbsent(eq("presence:hb:u1"), eq("1"), any())).thenReturn(true, false);

        presence.heartbeat("u1");
        presence.heartbeat("u1");

        assertTrue(presence.isPendingLastActive("u1"));
        verify(userRepository, never()).touchLastActive(eq("u1"), any());
        verify(depositScan, never()).ensureRegistered("u1");
        verify(publisher, never()).userBecameActive(eq("u1"), any());
    }

    @Test
    void loginActiveWritesImmediately() {
        when(userRepository.touchLastActive(eq("u1"), any())).thenReturn(1);

        presence.loginActive("u1");

        verify(userRepository).touchLastActive(eq("u1"), any(Instant.class));
        verify(depositScan).ensureRegistered("u1");
        verify(publisher).userBecameActive(eq("u1"), any(Instant.class));
        assertFalse(presence.isPendingLastActive("u1"));
    }

    @Test
    void flushBatchUpdatesOutsideHeartbeat() {
        when(values.setIfAbsent(eq("presence:hb:u1"), eq("1"), any())).thenReturn(true);
        presence.heartbeat("u1");

        PresenceLastActiveFlushJob job = new PresenceLastActiveFlushJob(
            presence, userRepository, depositScan, publisher, new PresenceProperties(30, 200, 5000, 200));
        job.flush();

        verify(userRepository).touchLastActiveBatch(eq(List.of("u1")), any(Instant.class));
        verify(userRepository, never()).touchLastActive(eq("u1"), any());
        verify(depositScan).ensureRegistered("u1");
        assertEquals(0, presence.drainPendingLastActive(10).size());
    }
}
