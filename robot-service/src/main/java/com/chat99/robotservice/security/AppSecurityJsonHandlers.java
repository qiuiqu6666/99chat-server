package com.chat99.robotservice.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.AuthenticationEntryPoint;

/** 与主服务保持一致的 401 JSON 错误体。 */
public final class AppSecurityJsonHandlers {

    public static final String CODE_UNAUTHORIZED = "UNAUTHORIZED";

    private AppSecurityJsonHandlers() {}

    public static AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> writeUnauthorized(response);
    }

    public static void writeUnauthorized(HttpServletResponse response) throws IOException {
        writeJson(response, HttpStatus.UNAUTHORIZED.value(), CODE_UNAUTHORIZED, "invalid or expired token");
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
