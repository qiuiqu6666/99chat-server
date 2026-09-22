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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class StarredFriendServiceTest {

    @Mock
    StarredFriendRepository starredRepository;

    @Mock
    UserRepository userRepository;

    private StarredFriendService service;

    @BeforeEach
    void setUp() {
        service = new StarredFriendService(starredRepository, userRepository);
    }

    @Test
    void star_createsRow() {
        when(userRepository.findByUserId("friend1")).thenReturn(Optional.of(activeUser("friend1")));
        when(starredRepository.findByUserIdAndFriendUserId("me", "friend1")).thenReturn(Optional.empty());
        when(starredRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var r = service.star("me", "friend1");

        assertThat(r.starred()).isTrue();
        assertThat(r.friendUserId()).isEqualTo("friend1");
        verify(starredRepository).save(any());
    }

    @Test
    void star_selfRejected() {
        assertThatThrownBy(() -> service.star("me", "me"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason())
                .isEqualTo("CANNOT_STAR_SELF"));
    }

    private static User activeUser(String userId) {
        User u = new User();
        u.setUserId(userId);
        u.setPhone("+8613800000001");
        u.setPasswordHash("x");
        u.setNickname("n");
        u.setAvatarUrl("https://example.com/a.png");
        u.setStatus(1);
        return u;
    }
}
