package com.chat99.server.call;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.trtc.callback")
public record CallProperties(
    boolean enabled,
    String allowedSdkAppIds,
    String callbackToken,
    boolean bootstrapOnStartup,
    String callbackUrl
) {
    public CallProperties {
        if (allowedSdkAppIds == null) {
            allowedSdkAppIds = "";
        }
        if (callbackUrl == null) {
            callbackUrl = "";
        }
    }
}
