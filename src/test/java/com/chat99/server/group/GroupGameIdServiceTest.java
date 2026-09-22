package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GroupGameIdServiceTest {

    @Mock
    GroupProfileRepository profileRepository;

    @Mock
    GroupSettingsRepository settingsRepository;

    @Mock
    ImAdminClient imAdminClient;

    @Mock
    MeGroupsListCache meGroupsListCache;

    private GroupGameIdService service;

    @BeforeEach
    void setUp() {
        service = new GroupGameIdService(
            profileRepository, settingsRepository, imAdminClient, meGroupsListCache);
    }

    @Test
    void setGameid_normalizesBlankToEmpty() {
        when(profileRepository.findById("g1")).thenReturn(Optional.empty());
        when(settingsRepository.findById("g1")).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.setGameid("g1", "   ")).isEqualTo("");
        verify(imAdminClient).modifyGroupAppDefinedGameid("g1", "");
    }

    @Test
    void setGameid_rejectsTooLong() {
        String tooLong = "x".repeat(129);
        assertThatThrownBy(() -> service.setGameid("g1", tooLong))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(e -> ((ResponseStatusException) e).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(profileRepository, never()).save(any());
        verify(imAdminClient, never()).modifyGroupAppDefinedGameid(anyString(), anyString());
    }

    @Test
    void setGameid_writesSameValueToBothTables() {
        when(profileRepository.findById("g1")).thenReturn(Optional.empty());
        when(settingsRepository.findById("g1")).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setGameid("g1", "BDEP-T685-JSWQ");

        ArgumentCaptor<GroupProfile> profileCaptor = ArgumentCaptor.forClass(GroupProfile.class);
        ArgumentCaptor<GroupSettings> settingsCaptor = ArgumentCaptor.forClass(GroupSettings.class);
        verify(profileRepository).save(profileCaptor.capture());
        verify(settingsRepository).save(settingsCaptor.capture());
        assertThat(profileCaptor.getValue().getGameid()).isEqualTo("BDEP-T685-JSWQ");
        assertThat(settingsCaptor.getValue().getGameid()).isEqualTo("BDEP-T685-JSWQ");
        verify(imAdminClient).modifyGroupAppDefinedGameid("g1", "BDEP-T685-JSWQ");
        verify(meGroupsListCache).invalidateGroup("g1");
    }

    @Test
    void setGameid_imFailure_doesNotInvalidateCache() {
        when(profileRepository.findById("g1")).thenReturn(Optional.empty());
        when(settingsRepository.findById("g1")).thenReturn(Optional.empty());
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new ImRestException("IM_REST_UNAVAILABLE", 0))
            .when(imAdminClient).modifyGroupAppDefinedGameid("g1", "BDEP-T685-JSWQ");

        assertThatThrownBy(() -> service.setGameid("g1", "BDEP-T685-JSWQ"))
            .isInstanceOf(ImRestException.class);
        verify(meGroupsListCache, never()).invalidateGroup(anyString());
    }
}
