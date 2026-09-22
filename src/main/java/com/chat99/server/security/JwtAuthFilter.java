/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.security;

import com.chat99.server.security.AppSecurityJsonHandlers;
import com.chat99.server.security.JwtService;
import com.chat99.server.security.UserSessionService;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter
extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final Pattern MEMBER_CHANGES_PATH =
        Pattern.compile("^/me/groups/[^/]+/members/changes$");
    private static final Pattern ROBOT_PATH = Pattern.compile("^/me/robot(?:/.*)?$");
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final UserSessionService sessionService;

    public JwtAuthFilter(JwtService jwtService, UserRepository userRepository, UserSessionService sessionService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.sessionService = sessionService;
    }

    protected boolean shouldNotFilter(HttpServletRequest request) {
        return JwtAuthFilter.isPublicAuthPath(request.getRequestURI());
    }

    static boolean isPublicAuthPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.startsWith("/api/v1/")) {
            return true;
        }
        if (path.equals("/auth/register")
            || path.equals("/auth/login")
            || path.equals("/auth/login/sms")
            || path.equals("/auth/login/password")
            || path.equals("/auth/login/password/verify")
            || path.equals("/auth/login/qr/session")
            || path.startsWith("/auth/login/qr/session/")
            || path.equals("/auth/password/reset")
            || path.startsWith("/auth/slider/")
            || path.equals("/sms/send")
            || path.equals("/nicknames/available")
            || path.equals("/platform/contact")
            || path.equals("/platform/customer-service")
            || path.equals("/platform/splash")
            || path.equals("/api/v1/platform/contact")
            || path.equals("/api/v1/platform/customer-service")
            || path.equals("/api/v1/platform/splash")
            || path.startsWith("/webhook/trtc/")
            || path.startsWith("/webhook/livekit/")
            || path.startsWith("/webhook/im/")
            || path.startsWith("/webhook/life-payment/")
            || path.startsWith("/integration/")
            || path.equals("/internal/chat/attachments/im-events")
            || path.startsWith("/chat-media/")
            || path.startsWith("/life-payments/tasks/")
            || path.startsWith("/life-payments/workers/")
            || path.startsWith("/api/internal/")
            || path.startsWith("/sangong/")
            || path.startsWith("/group-live/")
            || path.startsWith("/ai-assistant/")
            || path.equals("/kefu")
            || path.startsWith("/kefu/")
            || path.startsWith("/api/admin/robot-desk/")
            || path.startsWith("/error")
            || path.startsWith("/h2-console/")) {
            return true;
        }
        return false;
    }

    private static boolean allowsRevokedSessionCompatibility(HttpServletRequest request) {
        String path = request.getRequestURI();
        return ("GET".equals(request.getMethod())
                && MEMBER_CHANGES_PATH.matcher(path).matches())
            || ROBOT_PATH.matcher(path).matches();
    }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                String userId = this.jwtService.parseUserId(token);
                Optional user = this.userRepository.findByUserId(userId);
                if (user.isEmpty()) {
                    log.warn("JWT rejected user not found userId={} path={}", (Object)userId, (Object)request.getRequestURI());
                    AppSecurityJsonHandlers.writeUnauthorized((HttpServletResponse)response);
                    return;
                }
                if (((User)user.get()).getStatus() != 1) {
                    log.warn("JWT rejected account disabled userId={} path={}", (Object)userId, (Object)request.getRequestURI());
                    AppSecurityJsonHandlers.writeAccountDisabled((HttpServletResponse)response);
                    return;
                }
                Optional<String> jti = this.jwtService.parseJti(token);
                if (jti.isPresent() && !this.sessionService.isSessionActive(userId, jti.get())) {
                    if (allowsRevokedSessionCompatibility(request)) {
                        log.info("JWT revoked-session compatibility accepted userId={} path={}",
                            userId, request.getRequestURI());
                    } else {
                        log.warn("JWT rejected session revoked userId={} path={}", (Object)userId, (Object)request.getRequestURI());
                        AppSecurityJsonHandlers.writeSessionRevoked((HttpServletResponse)response);
                        return;
                    }
                }
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken((Object)userId, null, List.of());
                auth.setDetails((Object)new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication((Authentication)auth);
            }
            catch (JwtException e) {
                log.warn("Invalid JWT path={} reason={}", (Object)request.getRequestURI(), (Object)e.getMessage());
                AppSecurityJsonHandlers.writeUnauthorized((HttpServletResponse)response);
                return;
            }
        } else if (header == null) {
            log.debug("no Authorization header path={}", (Object)request.getRequestURI());
        }
        chain.doFilter((ServletRequest)request, (ServletResponse)response);
    }
}
