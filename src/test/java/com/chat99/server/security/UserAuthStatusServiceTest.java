package com.chat99.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserAuthStatusServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void loadsOnceUntilInvalidated() {
        User user = new User();
        user.setStatus(1);
        when(userRepository.findByUserId("u1")).thenReturn(Optional.of(user));
        UserAuthStatusService service = new UserAuthStatusService(userRepository);

        assertEquals(1, service.resolve("u1").status());
        assertEquals(1, service.resolve("u1").status());
        verify(userRepository, times(1)).findByUserId("u1");

        service.invalidate("u1");
        assertEquals(1, service.resolve("u1").status());
        verify(userRepository, times(2)).findByUserId("u1");
    }

    @Test
    void cachesMissingUser() {
        when(userRepository.findByUserId("missing")).thenReturn(Optional.empty());
        UserAuthStatusService service = new UserAuthStatusService(userRepository);

        assertFalse(service.resolve("missing").found());
        assertFalse(service.resolve("missing").found());
        verify(userRepository, times(1)).findByUserId("missing");
    }
}
