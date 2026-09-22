package com.chat99.sangong.security;

import com.chat99.sangong.config.SangongProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ImCallbackKeyFilter extends OncePerRequestFilter {
    private final SangongProperties props;
    public ImCallbackKeyFilter(SangongProperties props) { this.props = props; }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/api/v1/im/callback");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String expected = props.getImCallbackKey() == null ? "" : props.getImCallbackKey();
        if (expected.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader("X-Im-Callback-Key");
        if (provided == null || provided.isBlank()) {
            provided = request.getParameter("callbackKey");
        }
        if (provided == null || !java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            JwtAuthFilter.writeJson(response, 401, "IM_CALLBACK_KEY_REQUIRED", "需要有效的 IM 回调密钥（X-Im-Callback-Key）");
            return;
        }
        chain.doFilter(request, response);
    }
}
