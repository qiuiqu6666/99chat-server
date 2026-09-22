package com.chat99.robotservice.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 与主服务共享同一 JWT 签名密钥（HS256，>=32 字符）。
 * 密钥值需与主库 chat99.app_setting 中 JWT_SECRET 保持一致。
 */
@ConfigurationProperties(prefix = "chat99.jwt")
public record JwtProperties(String secret) {
    public JwtProperties {
        if (secret == null) {
            secret = "";
        }
    }
}
