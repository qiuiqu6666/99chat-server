package com.chat99.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.user.PresenceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RealtimeTcpHandlerPresenceTest {

    private final ObjectMapper json = new ObjectMapper();

    @Mock RealtimeAuthService authService;
    @Mock RealtimeSessionRegistry sessions;
    @Mock PresenceService presenceService;

    private RealtimeProperties props;

    @BeforeEach
    void setUp() {
        props = new RealtimeProperties(
            true, 18082, 10, 90, 65536, 1000, 100, false, false, false, false);
        when(presenceService.maxBatchSize()).thenReturn(200);
        when(authService.authenticate(eq("tok"), any()))
            .thenReturn(Optional.of(new RealtimeAuthService.AuthResult("u1", "d0")));
    }

    @Test
    void ping_withDeviceId_updatesDeviceAndReturnsPongTs() throws Exception {
        EmbeddedChannel ch = openAuthed(Runnable::run);
        drain(ch);

        ch.writeInbound(json.writeValueAsString(Map.of("type", "ping", "deviceId", "dev-9")) + "\n");
        Map<String, Object> pong = readLast(ch);
        assertThat(pong.get("type")).isEqualTo("pong");
        assertThat(pong.get("ts")).isInstanceOf(Number.class);
        verify(presenceService).deviceHeartbeat("u1", "dev-9");
        verify(presenceService, never()).heartbeat("u1");
    }

    @Test
    void presenceLastSeen_ok() throws Exception {
        when(presenceService.lastSeen(eq("u1"), any()))
            .thenReturn(new PresenceService.LastSeenSnapshot(
                Map.of("a", 10L), Map.of("a", "everyone")));
        EmbeddedChannel ch = openAuthed(Runnable::run);
        drain(ch);

        ch.writeInbound(json.writeValueAsString(Map.of(
            "type", "presence_last_seen",
            "requestId", "req-1",
            "userIds", List.of("a"))) + "\n");
        Map<String, Object> ok = readLast(ch);
        assertThat(ok.get("type")).isEqualTo("presence_last_seen_ok");
        assertThat(ok.get("requestId")).isEqualTo("req-1");
        assertThat(ok.get("lastSeen")).isEqualTo(Map.of("a", 10));
        assertThat(ok.get("lastActiveVisibility")).isEqualTo(Map.of("a", "everyone"));
        assertThat(ok.get("ts")).isInstanceOf(Number.class);
    }

    @Test
    void presenceLastSeen_missingRequestId() throws Exception {
        EmbeddedChannel ch = openAuthed(Runnable::run);
        drain(ch);

        ch.writeInbound(json.writeValueAsString(Map.of(
            "type", "presence_last_seen",
            "userIds", List.of("a"))) + "\n");
        Map<String, Object> fail = readLast(ch);
        assertThat(fail.get("type")).isEqualTo("presence_last_seen_fail");
        assertThat(fail.get("code")).isEqualTo("INVALID_INPUT");
        verify(presenceService, never()).lastSeen(any(), any());
    }

    @Test
    void presenceLastSeen_tooManyInflight() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> asyncErr = new AtomicReference<>();
        Executor blocking = cmd -> new Thread(() -> {
            try {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release timeout");
                }
                cmd.run();
            } catch (Throwable t) {
                asyncErr.set(t);
            }
        }, "test-query").start();

        when(presenceService.lastSeen(eq("u1"), any()))
            .thenReturn(new PresenceService.LastSeenSnapshot(Map.of(), Map.of()));

        EmbeddedChannel ch = openAuthed(blocking);
        drain(ch);

        for (int i = 0; i < 3; i++) {
            ch.writeInbound(json.writeValueAsString(Map.of(
                "type", "presence_last_seen",
                "requestId", "r" + i,
                "userIds", List.of("a"))) + "\n");
        }
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        // allow first worker to mark inflight; wait briefly for all three to enqueue
        Thread.sleep(50);

        ch.writeInbound(json.writeValueAsString(Map.of(
            "type", "presence_last_seen",
            "requestId", "r-overflow",
            "userIds", List.of("a"))) + "\n");
        Map<String, Object> fail = readLast(ch);
        assertThat(fail.get("type")).isEqualTo("presence_last_seen_fail");
        assertThat(fail.get("requestId")).isEqualTo("r-overflow");
        assertThat(fail.get("code")).isEqualTo("TOO_MANY_INFLIGHT");

        release.countDown();
        Thread.sleep(100);
        assertThat(asyncErr.get()).isNull();
        ch.finish();
    }

    private EmbeddedChannel openAuthed(Executor queryExecutor) throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(new RealtimeTcpHandler(
            authService, sessions, props, json, presenceService, queryExecutor));
        ch.writeInbound(json.writeValueAsString(Map.of(
            "type", "auth", "token", "tok", "deviceId", "d0")) + "\n");
        verify(sessions, atLeastOnce()).register(eq("u1"), any());
        return ch;
    }

    private List<Map<String, Object>> drain(EmbeddedChannel ch) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Object msg;
        while ((msg = ch.readOutbound()) != null) {
            out.add(json.readValue(String.valueOf(msg).trim(), new TypeReference<>() {}));
        }
        return out;
    }

    private Map<String, Object> readLast(EmbeddedChannel ch) throws Exception {
        List<Map<String, Object>> all = drain(ch);
        assertThat(all).isNotEmpty();
        return all.get(all.size() - 1);
    }
}
