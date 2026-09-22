/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.adminapi;

import com.chat99.server.adminapi.AdminAccount;
import com.chat99.server.adminapi.AdminAccountRepository;
import com.chat99.server.adminapi.AdminJwtService;
import com.chat99.server.adminapi.AdminPrincipal;
import com.chat99.server.adminapi.AdminSessionService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminApiAuthFilter
extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminApiAuthFilter.class);
    private final AdminJwtService adminJwtService;
    private final AdminAccountRepository accountRepository;
    private final AdminSessionService sessionService;

    public AdminApiAuthFilter(AdminJwtService adminJwtService, AdminAccountRepository accountRepository, AdminSessionService sessionService) {
        this.adminJwtService = adminJwtService;
        this.accountRepository = accountRepository;
        this.sessionService = sessionService;
    }

    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        if (path.equals("/api/v1/lotteries/mark-six-demo")
                || path.startsWith("/api/v1/lotteries/mark-six-demo/")) {
            return true;
        }
        return path.equals("/api/v1/auth/login")
            || path.equals("/api/v1/platform/contact")
            || path.equals("/api/v1/platform/customer-service")
            || path.equals("/api/v1/platform/splash");
    }

    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String token = AdminApiAuthFilter.resolveToken(request);
        if (token == null || token.isBlank()) {
            this.writeUnauthorized(response);
            return;
        }
        try {
            AdminJwtService.AdminTokenClaims claims = this.adminJwtService.parse(token);
            if (!this.sessionService.isActive(claims.username(), claims.jti())) {
                this.writeUnauthorized(response);
                return;
            }
            List<String> permissions = this.resolvePermissions(claims);
            if (permissions == null) {
                this.writeUnauthorized(response);
                return;
            }
            AdminPrincipal principal = new AdminPrincipal(claims.username(), permissions);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken((Object)principal, null, List.of());
            SecurityContextHolder.getContext().setAuthentication((Authentication)auth);
            chain.doFilter((ServletRequest)request, (ServletResponse)response);
        }
        catch (JwtException e) {
            log.warn("admin-api JWT rejected path={} reason={}", (Object)request.getRequestURI(), (Object)e.getMessage());
            this.writeUnauthorized(response);
        }
    }

    private List<String> resolvePermissions(AdminJwtService.AdminTokenClaims claims) {
        return this.accountRepository.findByUsername(claims.username()).filter(AdminAccount::isEnabled).map(account -> Arrays.stream(account.getPermissionsCsv().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()).orElse(null);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"invalid_token\"}");
    }

    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        String query = request.getParameter("access_token");
        if ("/api/v1/admin/realtime/events".equals(request.getRequestURI()) && query != null && !query.isBlank()) {
            return query.trim();
        }
        return null;
    }
}
