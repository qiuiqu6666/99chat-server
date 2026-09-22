package com.chat99.server.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.device")
public record DeviceProperties(
    String trustMode,
    boolean loginCheckEnabled,
    int maxActiveSessionsPerPlatform) {

    public DeviceProperties {
        if (maxActiveSessionsPerPlatform <= 0) {
            maxActiveSessionsPerPlatform = 3;
        }
    }
}
