package com.chat99.server.group;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.group-projection-sync")
public record GroupProjectionSyncProperties(
    int maxGroups,
    int memberPageSize,
    int delayBetweenGroupsMs,
    boolean syncUsersAfterGroups,
    int userBatchSize,
    int delayBetweenUsersMs,
    int maxUsers,
    boolean runOnStartup,
    int maxGroupsOnStartup) {

    public GroupProjectionSyncProperties {
        if (maxGroups <= 0) {
            maxGroups = 10_000;
        }
        if (memberPageSize <= 0) {
            memberPageSize = 200;
        }
        if (delayBetweenGroupsMs < 0) {
            delayBetweenGroupsMs = 50;
        }
        if (userBatchSize <= 0) {
            userBatchSize = 50;
        }
        if (delayBetweenUsersMs < 0) {
            delayBetweenUsersMs = 20;
        }
        if (maxUsers <= 0) {
            maxUsers = Integer.MAX_VALUE;
        }
        if (maxGroupsOnStartup <= 0) {
            maxGroupsOnStartup = maxGroups;
        }
    }
}
