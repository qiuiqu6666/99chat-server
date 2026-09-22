package com.chat99.server.user;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NicknameServiceImMappingTest {

    @Mock UserRepository userRepository;
    @Mock NicknameProperties props;
    @Mock ImAdminClient imAdmin;
    @Mock ImUserIdService imUserIdService;
    @Mock UserFriendService userFriendService;

    private NicknameService service;

    @BeforeEach
    void setUp() {
        service = new NicknameService(userRepository, props, imAdmin, imUserIdService, userFriendService);
        when(props.minLength()).thenReturn(1);
        when(props.maxLength()).thenReturn(32);
        when(props.cooldownDays()).thenReturn(0);
    }

    @Test
    void updateNickname_profileUpdateUsesSameUserId() {
        User u = new User();
        u.setUserId("q14gkm5swv");
        u.setStatus(1);
        u.setNickname("old");
        when(userRepository.findByUserId("q14gkm5swv")).thenReturn(Optional.of(u));
        when(userRepository.existsByNicknameAndUserIdNot("新昵称", "q14gkm5swv")).thenReturn(false);
        when(imUserIdService.toIm("q14gkm5swv")).thenReturn("q14gkm5swv");

        service.update("q14gkm5swv", "新昵称");

        verify(imAdmin).profileUpdate(eq("q14gkm5swv"), eq("新昵称"));
    }
}
