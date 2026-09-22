package com.chat99.sangong.security;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.service.UserService;
import com.chat99.sangong.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserService userService;
    public JwtAuthFilter(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService; this.userService = userService;
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/v1/me/") && !p.equals("/api/v1/rounds/current") && !p.equals("/api/v1/bets");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeJson(response, 401, "UNAUTHORIZED", "缺少 Bearer Token");
            return;
        }
        try {
            // 主服务 JWT：sub 即主服务用户 ID（= IM 用户 ID），按当前租户查询或建档
            Claims claims = jwtService.parse(header.substring(7));
            if (TenantContext.get() == null || TenantContext.get().isBlank()) {
                writeJson(response, 400, "TENANT_REQUIRED", "需要 X-Tenant-Id（选择游戏群）");
                return;
            }
            String sub = claims.getSubject();
            if (sub == null || sub.isBlank()) {
                writeJson(response, 401, "UNAUTHORIZED", "Token 无效");
                return;
            }
            SangongUser user = userService.findOrCreateByImUserId(sub.trim(), null);
            if (user == null) {
                writeJson(response, 401, "UNAUTHORIZED", "用户不存在");
                return;
            }
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
            request.setAttribute("auth_user", user);
            chain.doFilter(request, response);
        } catch (JwtException e) {
            writeJson(response, 401, "UNAUTHORIZED", "Token 无效");
        } catch (IllegalStateException e) {
            writeJson(response, 500, "SERVER_MISCONFIG", e.getMessage());
        }
    }
    static void writeJson(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"ok\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
