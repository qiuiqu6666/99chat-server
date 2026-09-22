package com.chat99.server.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.nickname")
public record NicknameProperties(int cooldownDays, int minLength, int maxLength) {
    public NicknameProperties {
        if (cooldownDays <= 0) cooldownDays = 7;
        if (minLength <= 0) minLength = 2;
        if (maxLength <= 0) maxLength = 32;
    }
}
