package com.chat99.server.im;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImOnlineKickServiceTest {

    @Mock ImAdminClient imAdmin;
    @Mock ImUserIdService imUserIdService;

    private ImOnlineKickService service;

    @BeforeEach
    void setUp() {
        service = new ImOnlineKickService(imAdmin, imUserIdService);
        lenient().when(imUserIdService.toIm("user1")).thenReturn("user1");
    }

    @Test
    void resolveInstance_prefersCustomIdentifier() {
        List<ImAdminClient.OnlineInstance> online = List.of(
            new ImAdminClient.OnlineInstance(111L, "Android", "dev-a", "Online"),
            new ImAdminClient.OnlineInstance(222L, "Android", "dev-b", "Online"));

        Optional<Long> inst = ImOnlineKickService.resolveInstance(
            online, new ImOnlineKickService.DeviceKickTarget("dev-b", "android"), Set.of());

        assertThat(inst).contains(222L);
    }

    @Test
    void resolveInstance_fallsBackToPlatformMatch() {
        List<ImAdminClient.OnlineInstance> online = List.of(
            new ImAdminClient.OnlineInstance(333L, "iPhone", "", "Online"));

        Optional<Long> inst = ImOnlineKickService.resolveInstance(
            online, new ImOnlineKickService.DeviceKickTarget("dev-ios", "ios"), Set.of());

        assertThat(inst).contains(333L);
    }

    @Test
    void resolveInstance_doesNotPlatformFallbackOntoOtherCustomIdentifier() {
        List<ImAdminClient.OnlineInstance> online = List.of(
            new ImAdminClient.OnlineInstance(100L, "Android", "dev-current", "Online"));

        Optional<Long> inst = ImOnlineKickService.resolveInstance(
            online, new ImOnlineKickService.DeviceKickTarget("dev-old", "android"), Set.of());

        assertThat(inst).isEmpty();
    }

    @Test
    void kickDevices_protectsCurrentDeviceInstance() {
        when(imAdmin.queryOnlineInstances("user1")).thenReturn(List.of(
            new ImAdminClient.OnlineInstance(100L, "Android", "dev-current", "Online"),
            new ImAdminClient.OnlineInstance(200L, "Android", "dev-old", "Online")));

        service.kickDevices(
            "user1",
            List.of(new ImOnlineKickService.DeviceKickTarget("dev-old", "android")),
            Set.of("dev-current"));

        verify(imAdmin).adminKickDevices(eq("user1"), eq(List.of(200L)));
    }

    @Test
    void kickDevices_callsAdminKickWithResolvedInstIds() {
        when(imAdmin.queryOnlineInstances("user1")).thenReturn(List.of(
            new ImAdminClient.OnlineInstance(100L, "Android", "dev-old", "Online"),
            new ImAdminClient.OnlineInstance(200L, "Android", "dev-new", "Online")));

        service.kickDevices("user1", List.of(
            new ImOnlineKickService.DeviceKickTarget("dev-old", "android")));

        verify(imAdmin).adminKickDevices(eq("user1"), eq(List.of(100L)));
    }

    @Test
    void kickDevices_skipsWhenNoOnlineInstances() {
        when(imAdmin.queryOnlineInstances("user1")).thenReturn(List.of());

        service.kickDevices("user1", List.of(
            new ImOnlineKickService.DeviceKickTarget("dev-old", "android")));

        verify(imAdmin, never()).adminKickDevices(eq("user1"), anyList());
    }

    @Test
    void kickDevices_swallowsQueryFailure() {
        when(imAdmin.queryOnlineInstances("user1")).thenThrow(new IllegalStateException("im down"));

        service.kickDevices("user1", List.of(
            new ImOnlineKickService.DeviceKickTarget("dev-old", "android")));

        verify(imAdmin, never()).adminKickDevices(eq("user1"), anyList());
    }

    @Test
    void invalidateLoginState_callsKickAccount() {
        service.invalidateLoginState("user1");

        verify(imAdmin).kickAccount("user1");
    }

    @Test
    void invalidateLoginState_swallowsFailure() {
        org.mockito.Mockito.doThrow(new IllegalStateException("im down")).when(imAdmin).kickAccount("user1");

        service.invalidateLoginState("user1");

        verify(imAdmin).kickAccount("user1");
    }
}
