package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GamePrivilegeServiceTest {

    @Mock
    UserRepository userRepository;

    private GamePrivilegeService service;

    @BeforeEach
    void setUp() {
        service = new GamePrivilegeService(userRepository, new GamePrivilegeProperties(true, false));
    }

    @Test
    void getForUser_defaultsDisabled() {
        User user = user("u1", false);
        when(userRepository.findByUserId("u1")).thenReturn(Optional.of(user));

        GamePrivilegeService.GameView view = service.getForUser("u1");

        assertThat(view.gameEnabled()).isFalse();
    }

    @Test
    void getForUser_returnsEnabledForPrivilegedUser() {
        User user = user("u1", true);
        when(userRepository.findByUserId("u1")).thenReturn(Optional.of(user));

        GamePrivilegeService.GameView view = service.getForUser("u1");

        assertThat(view.gameEnabled()).isTrue();
    }

    @Test
    void getForUser_respectsMasterSwitch() {
        service = new GamePrivilegeService(userRepository, new GamePrivilegeProperties(false, false));
        User user = user("u1", true);
        when(userRepository.findByUserId("u1")).thenReturn(Optional.of(user));

        GamePrivilegeService.GameView view = service.getForUser("u1");

        assertThat(view.gameEnabled()).isFalse();
    }

    @Test
    void setPrivileged_savesFlag() {
        User user = user("u1", false);
        when(userRepository.findByUserId("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GamePrivilegeService.GameAdminView view = service.setPrivileged("u1", true);

        assertThat(view.gamePrivileged()).isTrue();
        assertThat(view.gameEnabledEffective()).isTrue();
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        assertThat(cap.getValue().isGamePrivileged()).isTrue();
    }

    @Test
    void getForUser_disabledUserForbidden() {
        User user = user("u1", true);
        user.setStatus(0);
        when(userRepository.findByUserId("u1")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.getForUser("u1"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason())
                .isEqualTo("USER_DISABLED"));
    }

    private static User user(String userId, boolean gamePrivileged) {
        User user = new User();
        user.setUserId(userId);
        user.setStatus(1);
        user.setGamePrivileged(gamePrivileged);
        return user;
    }
}
