package com.chat99.server.security;

import com.chat99.server.common.ApiErrorMessages;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * App 接口统一 JSON 错误体，便于客户端区分「Token 失效」与「账号禁用」。
 */
public final class AppSecurityJsonHandlers {

    public static final String CODE_UNAUTHORIZED = "UNAUTHORIZED";
    public static final String CODE_SESSION_REVOKED = "SESSION_REVOKED";

    private AppSecurityJsonHandlers() {}

    public static AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) ->
            writeJson(response, HttpStatus.UNAUTHORIZED.value(), CODE_UNAUTHORIZED,
                "invalid or expired token");
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            if (response.isCommitted()) {
                return;
            }
            writeJson(response, HttpStatus.FORBIDDEN.value(), ApiErrorMessages.CODE_ACCOUNT_DISABLED,
                ApiErrorMessages.MSG_ACCOUNT_DISABLED);
        };
    }

    public static void writeUnauthorized(HttpServletResponse response) throws IOException {
        writeJson(response, HttpStatus.UNAUTHORIZED.value(), CODE_UNAUTHORIZED,
            "invalid or expired token");
    }

    public static void writeSessionRevoked(HttpServletResponse response) throws IOException {
        writeJson(response, HttpStatus.UNAUTHORIZED.value(), CODE_SESSION_REVOKED,
            "session revoked");
    }

    public static void writeAccountDisabled(HttpServletResponse response) throws IOException {
        writeJson(response, HttpStatus.FORBIDDEN.value(), ApiErrorMessages.CODE_ACCOUNT_DISABLED,
            ApiErrorMessages.MSG_ACCOUNT_DISABLED);
    }

    private static void writeJson(HttpServletResponse response, int status, String code, String message)
        throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":\"" + escapeJson(code) + "\",\"message\":\"" + escapeJson(message) + "\"}";
        response.getWriter().write(body);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
