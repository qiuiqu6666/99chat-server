package com.chat99.server.group;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.group-privacy")
public record GroupPrivacyProperties(boolean defaultPrivacyProtectionEnabled) {

    public GroupPrivacyProperties {
        // 未在 DB 落库前，默认开启群隐私保护
    }
}
