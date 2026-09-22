package com.chat99.server.announcement;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.announcement.global-push")
public record AnnouncementGlobalPushProperties(
    boolean enabled,
    int batchSize,
    long delayBetweenUsersMs,
    int activeDays) {

    public AnnouncementGlobalPushProperties {
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (delayBetweenUsersMs < 0) {
            delayBetweenUsersMs = 150L;
        }
        if (activeDays < 0) {
            activeDays = 0;
        }
    }
}
