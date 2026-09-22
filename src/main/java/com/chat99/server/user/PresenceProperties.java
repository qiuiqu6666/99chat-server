package com.chat99.server.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.presence")
public record PresenceProperties(int heartbeatThrottleSeconds, int maxBatchSize) {
    public PresenceProperties {
        if (heartbeatThrottleSeconds <= 0) heartbeatThrottleSeconds = 30;
        if (maxBatchSize <= 0) maxBatchSize = 200;
    }
}
