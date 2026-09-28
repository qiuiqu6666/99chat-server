package com.chat99.server.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.im.webhook-dispatch")
public record ImWebhookDispatchProperties(
    boolean enabled,
    int corePoolSize,
    int maxPoolSize,
    int queueCapacity,
    int timeoutMs) {

    public ImWebhookDispatchProperties {
        if (corePoolSize <= 0) {
            corePoolSize = 8;
        }
        if (maxPoolSize < corePoolSize) {
            maxPoolSize = Math.max(32, corePoolSize);
        }
        if (queueCapacity <= 0) {
            queueCapacity = 200;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 5000;
        }
    }
}
