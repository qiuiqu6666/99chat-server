package com.chat99.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private static final String TOKEN = "token";
    private static final String USER_ID = "user-1";
    private static final String JTI = "session-1";

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionService sessionService;

    @Mock
    private FilterChain chain;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsRevokedSessionForMemberChanges() throws Exception {
        JwtAuthFilter filter = filterWithRevokedSession();
        MockHttpServletRequest request = authenticatedRequest(
            "GET", "/me/groups/group-1/members/changes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(USER_ID, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void allowsRevokedSessionForRobotEndpoints() throws Exception {
        JwtAuthFilter filter = filterWithRevokedSession();
        MockHttpServletRequest request = authenticatedRequest(
            "POST", "/me/robot/groups/group-1/bind");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(USER_ID, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    @Test
    void rejectsRevokedSessionForOtherEndpoints() throws Exception {
        JwtAuthFilter filter = filterWithRevokedSession();
        MockHttpServletRequest request = authenticatedRequest("GET", "/me/groups/changes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertEquals("{\"code\":\"SESSION_REVOKED\",\"message\":\"session revoked\"}",
            response.getContentAsString());
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void rejectsInvalidJwtForMemberChanges() throws Exception {
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, userRepository, sessionService);
        MockHttpServletRequest request = authenticatedRequest(
            "GET", "/me/groups/group-1/members/changes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtService.parseUserId(TOKEN)).thenThrow(new JwtException("invalid"));

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(request, response);
        verify(sessionService, never()).isSessionActive(USER_ID, JTI);
    }

    @Test
    void treatsChatMediaAsPublicAuthPath() {
        assertEquals(true, JwtAuthFilter.isPublicAuthPath("/chat-media/v1/abc.def"));
        assertEquals(false, JwtAuthFilter.isPublicAuthPath("/me/chat/native-video-messages/task-1"));
    }

    @Test
    void treatsKefuAsPublicAuthPath() {
        assertEquals(true, JwtAuthFilter.isPublicAuthPath("/kefu/public/api/v1/inboxes/x/contacts"));
        assertEquals(true, JwtAuthFilter.isPublicAuthPath("/kefu/cable"));
        assertEquals(false, JwtAuthFilter.isPublicAuthPath("/me/kefu"));
    }

    private JwtAuthFilter filterWithRevokedSession() {
        User user = new User();
        user.setStatus(1);
        when(jwtService.parseUserId(TOKEN)).thenReturn(USER_ID);
        when(jwtService.parseJti(TOKEN)).thenReturn(Optional.of(JTI));
        when(userRepository.findByUserId(USER_ID)).thenReturn(Optional.of(user));
        when(sessionService.isSessionActive(USER_ID, JTI)).thenReturn(false);
        return new JwtAuthFilter(jwtService, userRepository, sessionService);
    }

    private static MockHttpServletRequest authenticatedRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }
}
