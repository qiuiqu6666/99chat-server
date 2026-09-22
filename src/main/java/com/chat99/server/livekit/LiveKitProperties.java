package com.chat99.server.livekit;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.livekit")
public record LiveKitProperties(
    boolean enabled,
    String host,
    String apiKey,
    String apiSecret,
    int tokenTtlSeconds,
    int ringTimeoutSeconds,
    boolean webhookEnabled
) {
    public LiveKitProperties {
        if (host == null) {
            host = "";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (apiSecret == null) {
            apiSecret = "";
        }
        if (tokenTtlSeconds <= 0) {
            tokenTtlSeconds = 3600;
        }
        if (ringTimeoutSeconds <= 0) {
            ringTimeoutSeconds = 60;
        }
    }

    public boolean isConfigured() {
        return enabled
            && host != null && !host.isBlank()
            && apiKey != null && !apiKey.isBlank()
            && apiSecret != null && !apiSecret.isBlank();
    }
}
