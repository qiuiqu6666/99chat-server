package com.chat99.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.qr-login")
public record QrLoginProperties(int ttlSeconds, String siteLabel) {

    public QrLoginProperties {
        if (ttlSeconds <= 0) {
            ttlSeconds = 120;
        }
        if (siteLabel == null || siteLabel.isBlank()) {
            siteLabel = "网页版登录";
        }
    }
}
