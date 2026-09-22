package com.chat99.server.adminapi;

import com.chat99.server.common.ClientContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * 采集全部 HTTP 接口耗时与状态码，供运营后台「接口监控」页展示。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ApiRequestMetricsFilter extends OncePerRequestFilter {

    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final ApiRequestMetricsStore store;
    private final ClientContext clientContext;

    public ApiRequestMetricsFilter(ApiRequestMetricsStore store, ClientContext clientContext) {
        this.store = store;
        this.clientContext = clientContext;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATH_HELPER.getPathWithinApplication(request);
        if (path == null || path.isBlank()) {
            return true;
        }
        // 避免监控接口自反馈；静态与探活噪声跳过
        if (path.startsWith("/api/v1/admin/ops/api-metrics")) {
            return true;
        }
        if (path.startsWith("/assets/")
            || path.startsWith("/static/")
            || path.equals("/favicon.ico")
            || path.equals("/favicon.svg")
            || path.equals("/vite.svg")
            || path.startsWith("/h2-console")
            || path.equals("/error")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNs = System.nanoTime();
        Exception error = null;
        try {
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            error = e;
            throw e;
        } finally {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
            int status = response.getStatus();
            if (error != null && status < 400) {
                status = 500;
            }
            String path = resolvePath(request);
            String hint = error == null ? null : truncateMessage(error);
            store.record(
                request.getMethod(),
                path,
                status,
                durationMs,
                hint,
                clientContext.ip(request),
                clientContext.userAgent(request),
                clientContext.platform(request),
                clientContext.deviceModel(request));
        }
    }

    private static String resolvePath(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String s && !s.isBlank()) {
            return s;
        }
        String path = PATH_HELPER.getPathWithinApplication(request);
        return path == null || path.isBlank() ? request.getRequestURI() : path;
    }

    private static String truncateMessage(Throwable error) {
        String msg = error.getClass().getSimpleName();
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            msg = msg + ": " + error.getMessage();
        }
        return msg.length() > 240 ? msg.substring(0, 240) : msg;
    }
}
