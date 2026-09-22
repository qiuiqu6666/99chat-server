package com.chat99.server.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.notify-friend-backfill")
public record NotifyFriendBackfillProperties(
    boolean systemNotifyEnabled,
    boolean platformWalletEnabled,
    int batchSize,
    int delayBetweenUsersMs,
    boolean scheduledEnabled,
    long scheduledIntervalMs,
    int maxUsersPerScheduledRun,
    boolean runOnStartup,
    int maxUsersOnStartup) {

    public NotifyFriendBackfillProperties {
        if (batchSize <= 0) {
            batchSize = 100;
        }
        if (delayBetweenUsersMs < 0) {
            delayBetweenUsersMs = 50;
        }
        if (scheduledIntervalMs <= 0) {
            scheduledIntervalMs = 60_000L;
        }
        if (maxUsersPerScheduledRun <= 0) {
            maxUsersPerScheduledRun = 500;
        }
    }
}
