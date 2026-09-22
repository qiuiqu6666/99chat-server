package com.chat99.server.common;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 允许浏览器 / Flutter Web 跨域访问 App API（含登录、钱包等）。
 */
@Configuration
public class AppCorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
        @Value("${APP_API_CORS_ORIGINS:*}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedHeaders(List.of("*"));
        config.setAllowedMethods(List.of("*"));
        config.setMaxAge(3600L);
        config.setExposedHeaders(List.of(
            "Authorization", "Content-Length", "Content-Range", "Accept-Ranges"));

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (origins.isEmpty() || origins.contains("*")) {
            // 通配来源禁止与凭证组合（浏览器规范也不允许），否则等于反射任意站点为可信来源。
            // App 端鉴权走 Authorization 头而非 Cookie，无需 credentials；需要携带凭证时请配置显式白名单。
            config.setAllowedOriginPatterns(List.of("*"));
            config.setAllowCredentials(false);
        } else {
            config.setAllowedOrigins(origins);
            config.setAllowCredentials(true);
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
