package com.chat99.server.group;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupChangedPublisherMappingTest {

    @Mock ImAdminClient im;
    @Mock ImUserIdService imUserIdService;
    @Mock GroupRealtimePublisher groupRealtime;
    @Mock GroupProjectionService projection;
    @Mock GroupChangeEmitter changeEmitter;

    GroupChangedPublisher publisher;
    GroupFanoutTargetResolver fanoutTargets;

    @BeforeEach
    void setUp() {
        fanoutTargets = new GroupFanoutTargetResolver(projection, im, GroupFanoutProperties.defaults());
        publisher = new GroupChangedPublisher(
            imUserIdService, groupRealtime, projection, changeEmitter, fanoutTargets);
        when(projection.findProfile(anyString())).thenReturn(Optional.empty());
        when(imUserIdService.toBusinessForDisplay(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(imUserIdService.toBusinessForDisplayBatch(anyList())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<String> ids = inv.getArgument(0);
            Map<String, String> out = new HashMap<>();
            for (String id : ids) {
                out.put(id, id);
            }
            return out;
        });
    }

    @Test
    void publishToAll_usesLocalTargetsAndSkipsImList() {
        when(projection.listLocalMemberUserIds("g1")).thenReturn(List.of("q14gkm5swv", "alicebiz"));

        publisher.publishToAll("g1", GroupRealtimePublisher.ACTION_GROUP_NAME_CHANGED, "q14gkm5swv",
            List.of("alicebiz"), Map.of("k", "v"));

        verify(im, never()).listGroupMemberUserIds(anyString(), anyInt());
        verify(groupRealtime).publish(
            eq("g1"),
            eq(GroupRealtimePublisher.ACTION_GROUP_NAME_CHANGED),
            eq("q14gkm5swv"),
            eq(List.of("alicebiz")),
            eq(List.of("q14gkm5swv", "alicebiz")),
            org.mockito.ArgumentMatchers.any(),
            anyString(),
            anyLong(),
            org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishToAll_fallsBackToImWhenLocalEmpty() {
        when(projection.listLocalMemberUserIds("g1")).thenReturn(List.of());
        when(im.listGroupMemberUserIds(eq("g1"), anyInt())).thenReturn(List.of("q14gkm5swv", "alicebiz"));

        publisher.publishToAll("g1", GroupRealtimePublisher.ACTION_GROUP_NAME_CHANGED, "q14gkm5swv",
            List.of("alicebiz"), Map.of("k", "v"));

        verify(im).listGroupMemberUserIds(eq("g1"), anyInt());
        verify(groupRealtime).publish(
            eq("g1"),
            eq(GroupRealtimePublisher.ACTION_GROUP_NAME_CHANGED),
            eq("q14gkm5swv"),
            eq(List.of("alicebiz")),
            eq(List.of("q14gkm5swv", "alicebiz")),
            org.mockito.ArgumentMatchers.any(),
            anyString(),
            anyLong(),
            org.mockito.ArgumentMatchers.any());
    }
}
