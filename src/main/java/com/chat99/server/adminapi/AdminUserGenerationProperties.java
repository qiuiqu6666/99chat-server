package com.chat99.server.adminapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.admin-user-generation")
public record AdminUserGenerationProperties(
    String encryptionKey,
    long pollIntervalMs,
    int retentionDays
) {
    public AdminUserGenerationProperties {
        encryptionKey = encryptionKey == null ? "" : encryptionKey.trim();
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 1000;
        }
        if (retentionDays <= 0) {
            retentionDays = 30;
        }
    }
}
