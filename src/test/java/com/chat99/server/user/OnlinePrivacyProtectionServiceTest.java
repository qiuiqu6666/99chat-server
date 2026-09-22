package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class OnlinePrivacyProtectionServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserPrivacyService privacyService;
    @Mock ApplicationEventPublisher events;

    @InjectMocks OnlinePrivacyProtectionService service;

    @Test
    void updateForSelf_publishesWhenVisibilityChanges() {
        User user = new User();
        user.setUserId("u1");
        user.setStatus(1);
        user.setLastActiveVisibility(LastActiveVisibility.everyone);
        when(userRepository.findByUserId("u1")).thenReturn(java.util.Optional.of(user));
        when(privacyService.lastActiveVisibilityOf(user)).thenReturn(LastActiveVisibility.everyone);

        service.updateForSelf("u1", LastActiveVisibility.hidden);

        ArgumentCaptor<OnlinePrivacyProtectionService.LastActiveVisibilityChangedEvent> captor =
            ArgumentCaptor.forClass(OnlinePrivacyProtectionService.LastActiveVisibilityChangedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo("u1");
        assertThat(captor.getValue().previous()).isEqualTo(LastActiveVisibility.everyone);
        assertThat(captor.getValue().current()).isEqualTo(LastActiveVisibility.hidden);
        verify(userRepository).save(user);
        assertThat(user.getLastActiveVisibility()).isEqualTo(LastActiveVisibility.hidden);
    }

    @Test
    void updateForSelf_skipsEventWhenUnchanged() {
        User user = new User();
        user.setUserId("u1");
        user.setStatus(1);
        user.setLastActiveVisibility(LastActiveVisibility.hidden);
        when(userRepository.findByUserId("u1")).thenReturn(java.util.Optional.of(user));
        when(privacyService.lastActiveVisibilityOf(user)).thenReturn(LastActiveVisibility.hidden);

        service.updateForSelf("u1", LastActiveVisibility.hidden);

        verify(events, never()).publishEvent(any());
        verify(userRepository, never()).save(any());
    }
}
