package com.chat99.sangong.security;

import com.chat99.sangong.service.TenantService;
import com.chat99.sangong.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 管理端 / 玩家端解析当前租户：
 * Header {@code X-Tenant-Id} 或 query {@code tenantId}。
 * 租户列表/创建接口不要求。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantFilter extends OncePerRequestFilter {
    private final TenantService tenants;

    public TenantFilter(TenantService tenants) {
        this.tenants = tenants;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        if (p.startsWith("/api/v1/admin/tenants") || p.startsWith("/api/v1/admin/my-config")) {
            return true;
        }
        if (p.equals("/api/v1/admin/auth/login")) return true;
        return !(p.startsWith("/api/v1/admin/")
            || p.startsWith("/api/v1/me/")
            || p.equals("/api/v1/rounds/current")
            || p.equals("/api/v1/bets")
            || p.equals("/api/v1/settings"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try {
            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId == null || tenantId.isBlank()) {
                tenantId = request.getParameter("tenantId");
            }
            boolean admin = request.getRequestURI().startsWith("/api/v1/admin/")
                || request.getRequestURI().equals("/api/v1/settings");
            if (tenantId == null || tenantId.isBlank()) {
                if (admin) {
                    JwtAuthFilter.writeJson(response, 400, "TENANT_REQUIRED", "需要 X-Tenant-Id（选择游戏群）");
                    return;
                }
                // 玩家路径(ctx 暂时缺):继续走链路,由 JwtAuthFilter 在校验完 JWT 后再二次校验 ctx
                // 若仍缺 —返回 400 TENANT_REQUIRED。当前没有任何逻辑从 JWT claim 反向推导出 tenantId,
                // 策划上是显式 X-Tenant-Id 必填。
                chain.doFilter(request, response);
                return;
            }
            String id = tenantId.trim();
            var tenant = tenants.find(id);
            if (tenant.isEmpty() || !tenant.get().isActive()) {
                JwtAuthFilter.writeJson(response, 404, "TENANT_NOT_FOUND", "游戏群不存在或已停用");
                return;
            }
            TenantContext.set(id);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
