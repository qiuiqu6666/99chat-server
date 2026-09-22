package com.chat99.server.im.restqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ImRestRateLimiterTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    ImRestRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        limiter = new ImRestRateLimiter(redis, new ImRestQueueProperties(
            true, "t", "d", "g", 1, 10, 10, 8, false, true,
            Map.of("get-role-in-group", 2)));
    }

    @Test
    void acquire_allowsUntilLimit() {
        when(valueOps.increment("im:rl:get-role-in-group")).thenReturn(1L, 2L, 3L);
        assertThat(limiter.acquire("get-role-in-group", 0)).isTrue();
        assertThat(limiter.acquire("get-role-in-group", 0)).isTrue();
        assertThat(limiter.acquire("get-role-in-group", 0)).isFalse();
        verify(valueOps).decrement("im:rl:get-role-in-group");
    }
}
