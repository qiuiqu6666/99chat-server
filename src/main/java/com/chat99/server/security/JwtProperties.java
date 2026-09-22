/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="chat99.jwt")
public record JwtProperties(long expireSeconds, String secret) {
    public JwtProperties {
        if (expireSeconds <= 0L) {
            expireSeconds = 7776000L;
        }
        if (secret == null) {
            secret = "";
        }
    }
}
