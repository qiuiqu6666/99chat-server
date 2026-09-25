package com.chat99.server.group;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.group-create-limit")
public record GroupCreateLimitProperties(
    boolean enabled,
    int maxWorkGroups,
    int maxCommunityGroups,
    int maxJoinGroups,
    int maxCommunityJoinGroups,
    boolean enforce,
    boolean logOnly,
    boolean useImCountFallback) {

    public GroupCreateLimitProperties {
        if (maxWorkGroups < 0) {
            maxWorkGroups = 5;
        }
        if (maxCommunityGroups < 0) {
            maxCommunityGroups = 3;
        }
        if (maxJoinGroups < 0) {
            maxJoinGroups = 10000;
        }
        if (maxCommunityJoinGroups < 0) {
            maxCommunityJoinGroups = 1000;
        }
    }

    /** 建群数量不限制；Community 只靠付费。 */
    public int limitForType(String groupType) {
        return -1;
    }
}
