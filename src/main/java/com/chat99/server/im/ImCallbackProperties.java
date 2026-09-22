package com.chat99.server.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.im.callback")
public record ImCallbackProperties(
    boolean enabled,
    String callbackToken,
    String allowedSdkAppIds,
    boolean chatPushEnabled,
    boolean chatPushSkipWhenOnline,
    java.util.List<String> skipSenderIds,
    int maxGroupMembersPerPush,
    int dedupTtlHours,
    int groupPushAggSecondsSmall,
    int groupPushAggSecondsMedium,
    int groupPushAggSecondsLarge,
    int groupPushSmallGroupThreshold,
    int groupPushLargeGroupThreshold,
    int groupMemberCacheTtlMinutes,
    int groupPushFlushBatchSize,
    boolean groupMonitorEnabled,
    String groupMonitorUrl,
    String groupMonitorToken,
    String groupMonitorGroupId) {

    public ImCallbackProperties {
        if (skipSenderIds == null) {
            skipSenderIds = java.util.List.of();
        }
        if (maxGroupMembersPerPush < 0) {
            maxGroupMembersPerPush = 0;
        }
        if (dedupTtlHours <= 0) {
            dedupTtlHours = 48;
        }
        if (groupPushAggSecondsSmall < 0) {
            groupPushAggSecondsSmall = 10;
        }
        if (groupPushAggSecondsMedium <= 0) {
            groupPushAggSecondsMedium = 30;
        }
        if (groupPushAggSecondsLarge <= 0) {
            groupPushAggSecondsLarge = 60;
        }
        if (groupPushSmallGroupThreshold <= 0) {
            groupPushSmallGroupThreshold = 200;
        }
        if (groupPushLargeGroupThreshold <= 0) {
            groupPushLargeGroupThreshold = 2000;
        }
        if (groupMemberCacheTtlMinutes <= 0) {
            groupMemberCacheTtlMinutes = 10;
        }
        if (groupPushFlushBatchSize <= 0) {
            groupPushFlushBatchSize = 300;
        }
        if (groupMonitorUrl == null) {
            groupMonitorUrl = "";
        }
        if (groupMonitorToken == null) {
            groupMonitorToken = "";
        }
        if (groupMonitorGroupId == null) {
            groupMonitorGroupId = "";
        }
    }
}
