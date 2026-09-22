package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupPrivacyServiceTest {

    @Mock
    GroupSettingsRepository settingsRepository;

    @Mock
    com.chat99.server.realtime.GroupRealtimePublisher groupRealtime;

    @Mock
    GroupAccessService access;

    @Mock
    GroupFanoutTargetResolver fanoutTargets;

    private GroupPrivacyService service;

    @BeforeEach
    void setUp() {
        service = new GroupPrivacyService(
            settingsRepository, new GroupPrivacyProperties(true), groupRealtime, access, fanoutTargets);
    }

    @Test
    void get_defaultsWhenNoRow() {
        doNothing().when(access).requireMember("@TGS#_abc", "u1");
        when(settingsRepository.findById("@TGS#_abc")).thenReturn(Optional.empty());

        GroupPrivacyService.GroupPrivacyView view = service.get("@TGS#_abc", "u1");

        assertThat(view.privacyProtectionEnabled()).isTrue();
    }

    @Test
    void update_savesEnabled() {
        when(access.requireAdminRole("@TGS#_abc", "owner")).thenReturn("Owner");
        when(settingsRepository.findById("@TGS#_abc")).thenReturn(Optional.empty());
        when(settingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fanoutTargets.resolveMemberTargets("@TGS#_abc")).thenReturn(List.of("owner", "u2"));

        GroupPrivacyService.GroupPrivacyView view = service.update("@TGS#_abc", "owner", false);

        assertThat(view.privacyProtectionEnabled()).isFalse();
        ArgumentCaptor<GroupSettings> captor = ArgumentCaptor.forClass(GroupSettings.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().isPrivacyProtectionEnabled()).isFalse();
    }
}
