package com.chat99.server.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PushDisplayNameResolverTest {

    @Mock
    private ImAdminClient imAdmin;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ImUserIdService imUserIdService;

    @BeforeEach
    void stubToIm() {
        org.mockito.Mockito.lenient().when(imUserIdService.toIm(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void prefersFriendRemarkOverNickname() {
        when(imAdmin.resolveFriendDisplayName("callee1", "caller1"))
            .thenReturn(Optional.of("备注小明"));
        PushDisplayNameResolver resolver = new PushDisplayNameResolver(imAdmin, userRepository, imUserIdService);
        assertEquals("备注小明", resolver.resolveCallerDisplayName("callee1", "caller1"));
    }

    @Test
    void fallsBackToPlatformNickname() {
        when(imAdmin.resolveFriendDisplayName("callee1", "caller1"))
            .thenReturn(Optional.empty());
        User caller = new User();
        caller.setNickname("真实昵称");
        when(userRepository.findByUserId("caller1")).thenReturn(Optional.of(caller));
        PushDisplayNameResolver resolver = new PushDisplayNameResolver(imAdmin, userRepository, imUserIdService);
        assertEquals("真实昵称", resolver.resolveCallerDisplayName("callee1", "caller1"));
    }

    @Test
    void normalizesCompositeCallerIdBeforeLookup() {
        when(imAdmin.resolveFriendDisplayName("callee1", "caller1"))
            .thenReturn(Optional.of("备注"));
        PushDisplayNameResolver resolver = new PushDisplayNameResolver(imAdmin, userRepository, imUserIdService);
        assertEquals("备注", resolver.resolveCallerDisplayName("callee1", "caller1#0#0#callee1"));
    }

    @Test
    void fallsBackToUserIdWhenNoNickname() {
        when(imAdmin.resolveFriendDisplayName("callee1", "caller1"))
            .thenReturn(Optional.empty());
        when(userRepository.findByUserId("caller1")).thenReturn(Optional.empty());
        PushDisplayNameResolver resolver = new PushDisplayNameResolver(imAdmin, userRepository, imUserIdService);
        assertEquals("caller1", resolver.resolveCallerDisplayName("callee1", "caller1#0#0#callee1"));
    }
}
