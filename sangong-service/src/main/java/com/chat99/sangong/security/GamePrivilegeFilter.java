package com.chat99.sangong.security;

import com.chat99.sangong.service.MainGamePrivilegeService;
import com.chat99.sangong.service.TenantService;
import com.chat99.sangong.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理接口只允许主服务 users.game_privileged=true 的登录用户访问；
 * 并且账号只能操作自己有访问权的租户（游戏群），实现按账号隔离。
 */
@Component
public class GamePrivilegeFilter extends OncePerRequestFilter {
    private final MainGamePrivilegeService privileges;
    private final TenantService tenants;

    public GamePrivilegeFilter(MainGamePrivilegeService privileges, TenantService tenants) {
        this.privileges = privileges;
        this.tenants = tenants;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        if (p.equals("/api/v1/admin/auth/login")) return true;
        return !(p.startsWith("/api/v1/admin/")
            || ("PUT".equalsIgnoreCase(request.getMethod()) && p.equals("/api/v1/settings")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        MainGamePrivilegeService.CheckResult result =
            privileges.check(request.getHeader("Authorization"));
        switch (result.status()) {
            case ALLOWED -> {
                // 按账号隔离：租户已解析（非 /admin/tenants 管理路径）时校验账号-租户访问权
                String tenantId = TenantContext.get();
                if (tenantId != null && !request.getRequestURI().startsWith("/api/v1/admin/tenants")
                    && !tenants.canManage(result.userId(), tenantId)) {
                    JwtAuthFilter.writeJson(response, 403, "TENANT_ACCESS_DENIED", "该账号无权操作此游戏群");
                    return;
                }
                request.setAttribute("admin_user_id", result.userId());
                chain.doFilter(request, response);
            }
            case UNAUTHORIZED ->
                JwtAuthFilter.writeJson(response, 401, "UNAUTHORIZED", "需要有效的主服务登录 Token");
            case FORBIDDEN ->
                JwtAuthFilter.writeJson(response, 403, "GAME_PRIVILEGE_REQUIRED", "仅主服务游戏特权用户可操作");
            case UNAVAILABLE ->
                JwtAuthFilter.writeJson(response, 503, "PRIVILEGE_CHECK_UNAVAILABLE", "主服务权限校验暂不可用");
        }
    }
}
