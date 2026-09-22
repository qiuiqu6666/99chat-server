package com.chat99.server.adminapi;

import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
@ConditionalOnProperty(prefix = "chat99.admin-api", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdminApiWebConfig {

    @Bean
    public CorsFilter adminApiCorsFilter(AdminApiProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        var origins = Arrays.stream(props.corsOrigins().split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
        if (origins.isEmpty() || origins.contains("*")) {
            // 通配来源禁止与凭证组合，否则等于把任意站点反射为可信来源。
            // 后台鉴权走 Authorization 头（localStorage），无需 credentials；
            // 需携带凭证时请在 ADMIN_API_CORS_ORIGINS 配置显式白名单。
            config.addAllowedOriginPattern("*");
            config.setAllowCredentials(false);
        } else {
            origins.forEach(config::addAllowedOrigin);
            config.setAllowCredentials(true);
        }
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.addExposedHeader("Authorization");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", config);
        return new CorsFilter(source);
    }
}
