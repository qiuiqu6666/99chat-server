package com.chat99.server.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.user-friend-sync")
public record UserFriendSyncProperties(
    int batchSize,
    int delayBetweenUsersMs,
    boolean scheduledEnabled,
    long scheduledIntervalMs,
    int maxUsersPerScheduledRun,
    boolean runOnStartup,
    int maxUsersOnStartup) {

    public UserFriendSyncProperties {
        if (batchSize <= 0) {
            batchSize = 50;
        }
        if (delayBetweenUsersMs < 0) {
            delayBetweenUsersMs = 100;
        }
        if (scheduledIntervalMs <= 0) {
            scheduledIntervalMs = 300_000L;
        }
        if (maxUsersPerScheduledRun <= 0) {
            maxUsersPerScheduledRun = 200;
        }
    }
}
