package com.chat99.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.chat99.server.adminapi.AdminApiAuthFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AdminApiAuthFilter adminApiAuthFilter,
                                                   JwtAuthFilter jwtAuthFilter) throws Exception {
        return http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/platform/contact").permitAll()
                .requestMatchers("/api/v1/platform/customer-service").permitAll()
                .requestMatchers("/api/v1/platform/splash").permitAll()
                .requestMatchers("/api/v1/**").permitAll()
                .requestMatchers(
                    "/auth/register",
                    "/auth/login",
                    "/auth/login/sms",
                    "/auth/login/password",
                    "/auth/login/password/verify",
                    "/auth/login/qr/session",
                    "/auth/login/qr/session/**",
                    "/auth/password/reset",
                    "/auth/slider/init",
                    "/auth/slider/verify",
                    "/sms/send",
                    "/platform/contact",
                    "/platform/customer-service",
                    "/platform/splash",
                    "/nicknames/available",
                    "/webhook/trtc/**",
                    "/webhook/livekit/**",
                    "/webhook/im/**",
                    "/webhook/life-payment/**",
                    "/integration/**",
                    "/api/internal/**",
                    "/internal/chat/attachments/im-events",
                    "/chat-media/**",
                    "/life-payments/tasks/**",
                    "/life-payments/workers/**",
                    "/error",
                    "/h2-console/**").permitAll()
                .requestMatchers("/admin/**").permitAll()
                // 三公服务统一入口：鉴权由 sangong-service 自身完成（JWT / X-Settings-Key）
                .requestMatchers("/sangong/**").permitAll()
                // 群直播统一入口：鉴权由 group-live-service 自身完成（JWT）
                .requestMatchers("/group-live/**").permitAll()
                // AI 助手统一入口：鉴权由 ai-assistant-service 自身完成（JWT）
                .requestMatchers("/ai-assistant/**").permitAll()
                // 资金模块统一入口：代理时由 wallet-service 验 JWT；本地模式由 WalletController 验 JWT
                .requestMatchers("/wallet/**").permitAll()
                // 客服统一入口：访客 Client API 无 JWT，由主服务转发到本机 Chatwoot
                .requestMatchers("/kefu/**").permitAll()
                // 机器人台管理端：鉴权由上游 robot-service 完成（machineCode 等）
                .requestMatchers("/api/admin/robot-desk/**").permitAll()
                .anyRequest().authenticated())
            .headers(h -> h.frameOptions(f -> f.sameOrigin()))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(AppSecurityJsonHandlers.authenticationEntryPoint())
                .accessDeniedHandler(AppSecurityJsonHandlers.accessDeniedHandler()))
            .addFilterBefore(adminApiAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
