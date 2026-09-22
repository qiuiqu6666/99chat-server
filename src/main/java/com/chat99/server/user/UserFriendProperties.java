package com.chat99.server.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.user-friend")
public record UserFriendProperties(
    boolean beforeSendEnforce,
    boolean beforeSendLogOnly,
    long requestCooldownSeconds) {

    public UserFriendProperties {
        if (requestCooldownSeconds <= 0) {
            requestCooldownSeconds = 600L;
        }
    }
}
