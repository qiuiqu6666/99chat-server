/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.adminapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="chat99.admin-api")
public record AdminApiProperties(boolean enabled, String corsOrigins, long jwtExpireSeconds, int onlineThresholdMinutes, String jwtSecret) {
    public AdminApiProperties {
        if (jwtExpireSeconds <= 0L) {
            jwtExpireSeconds = 86400L;
        }
        if (onlineThresholdMinutes <= 0) {
            onlineThresholdMinutes = 5;
        }
        if (corsOrigins == null) {
            corsOrigins = "*";
        }
        if (jwtSecret == null) {
            jwtSecret = "";
        }
    }
}
