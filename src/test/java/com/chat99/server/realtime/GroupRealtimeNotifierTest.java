package com.chat99.server.realtime;

import com.chat99.server.group.GroupFanoutProperties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupRealtimeNotifierTest {

    @Mock RealtimeEventPublisher publisher;
    @Mock GroupRealtimeOfflinePushService offlinePush;
    @Mock RealtimeProperties props;

    GroupRealtimeNotifier notifier;
    Executor directExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        when(props.enabled()).thenReturn(true);
        GroupFanoutProperties fanout = new GroupFanoutProperties(true, true, 20_000, 200, 300, 2, 8, 500);
        notifier = new GroupRealtimeNotifier(publisher, offlinePush, props, fanout, directExecutor);
    }

    @Test
    void syncPath_sendsDirectlyForSmallTargetList() {
        when(publisher.sendToUser(anyString(), any())).thenReturn(true);
        when(offlinePush.shouldSkipOfflinePushForBroadcast(anyString())).thenReturn(false);

        GroupRealtimePublisher.GroupChangedEvent event = new GroupRealtimePublisher.GroupChangedEvent(
            "g1",
            GroupRealtimePublisher.ACTION_MEMBER_ADDED,
            "op",
            List.of("u1"),
            List.of("a", "b"),
            Map.of(),
            "ce1",
            System.currentTimeMillis(),
            1);
        notifier.onGroupChanged(event);

        verify(publisher, times(2)).sendToUser(anyString(), any());
    }

    @Test
    void asyncPath_chunkedDeliveryForLargeTargetList() {
        when(publisher.sendToUser(anyString(), any())).thenReturn(true);
        when(offlinePush.shouldSkipOfflinePushForBroadcast(anyString())).thenReturn(true);
        when(offlinePush.offlinePushAllowlist(any())).thenReturn(List.of());

        List<String> targets = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            targets.add("u" + i);
        }
        GroupRealtimePublisher.GroupChangedEvent event = new GroupRealtimePublisher.GroupChangedEvent(
            "g1",
            GroupRealtimePublisher.ACTION_GROUP_MUTE_ALL_CHANGED,
            "op",
            List.of(),
            targets,
            Map.of("shutUpAllMember", "On"),
            "ce2",
            System.currentTimeMillis(),
            1);
        notifier.onGroupChanged(event);

        verify(publisher, times(250)).sendToUser(anyString(), any());
        verify(offlinePush, never()).sendIfTcpUnreachable(anyString(), any());
    }

    @Test
    void muteAll_skipsBroadcastOfflinePush() {
        when(publisher.sendToUser(eq("a"), any())).thenReturn(false);
        when(publisher.sendToUser(eq("b"), any())).thenReturn(false);
        when(offlinePush.shouldSkipOfflinePushForBroadcast(
            GroupRealtimePublisher.ACTION_GROUP_MUTE_ALL_CHANGED)).thenReturn(true);
        when(offlinePush.offlinePushAllowlist(any())).thenReturn(List.of());

        GroupRealtimePublisher.GroupChangedEvent event = new GroupRealtimePublisher.GroupChangedEvent(
            "g1",
            GroupRealtimePublisher.ACTION_GROUP_MUTE_ALL_CHANGED,
            "op",
            List.of(),
            List.of("a", "b"),
            Map.of(),
            "ce3",
            System.currentTimeMillis(),
            1);
        notifier.onGroupChanged(event);

        verify(offlinePush, never()).sendIfTcpUnreachable(anyString(), any());
    }
}
