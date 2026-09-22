package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chat99.server.security.UserSessionService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserDeviceAppServiceTest {

    @Mock UserDeviceRepository deviceRepository;
    @Mock LoginLogRepository loginLogRepository;
    @Mock PresenceService presenceService;
    @Mock UserSessionService sessionService;
    @Mock DeviceModelDisplayService modelDisplay;

    private UserDeviceAppService service;

    @BeforeEach
    void setUp() {
        service = new UserDeviceAppService(
            deviceRepository, loginLogRepository, presenceService, sessionService, modelDisplay);
    }

    @Test
    void listDevices_returnsOnlyActiveSessions() {
        String userId = "user01";
        UserDevice active = device("dev-a", userId);
        UserDevice kicked = device("dev-b", userId);
        when(sessionService.listActiveDeviceIds(userId)).thenReturn(Set.of("dev-a"));
        when(deviceRepository.findByUserIdOrderByLastLoginAtDesc(userId))
            .thenReturn(List.of(active, kicked));
        when(modelDisplay.display("ios", "iPhone17,1")).thenReturn("iPhone 16 Pro");
        active.setModel("iPhone17,1");
        active.setPlatform("ios");
        when(loginLogRepository.findFirstByUserIdAndDeviceIdOrderByCreatedAtDesc(userId, "dev-a"))
            .thenReturn(java.util.Optional.empty());

        var response = service.listDevices(userId, "dev-a");

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).deviceId()).isEqualTo("dev-a");
        assertThat(response.items().get(0).model()).isEqualTo("iPhone 16 Pro");
        assertThat(response.items().get(0).isCurrent()).isTrue();
    }

    @Test
    void listDevices_emptyWhenNoActiveSessions() {
        when(sessionService.listActiveDeviceIds("user01")).thenReturn(Set.of());

        var response = service.listDevices("user01", "dev-a");

        assertThat(response.total()).isZero();
        assertThat(response.items()).isEmpty();
    }

    private static UserDevice device(String deviceId, String userId) {
        UserDevice d = new UserDevice();
        d.setDeviceId(deviceId);
        d.setUserId(userId);
        d.setPlatform("ios");
        d.setLastLoginAt(Instant.parse("2026-06-18T00:00:00Z"));
        d.setCreatedAt(Instant.parse("2026-06-17T00:00:00Z"));
        return d;
    }
}
