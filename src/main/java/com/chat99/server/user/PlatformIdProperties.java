package com.chat99.server.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.platform-id")
public record PlatformIdProperties(int length, String alphabet, int maxRetry) {
    public PlatformIdProperties {
        if (length <= 0) length = 10;
        if (alphabet == null || alphabet.isBlank()) alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        if (maxRetry <= 0) maxRetry = 5;
    }
}
