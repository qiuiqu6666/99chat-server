package com.chat99.server.group;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "chat99.group.fanout")
public record GroupFanoutProperties(
    @DefaultValue("true") boolean localTargetsEnabled,
    @DefaultValue("true") boolean asyncEnabled,
    @DefaultValue("20000") int maxMembers,
    @DefaultValue("200") int syncThreshold,
    @DefaultValue("300") int chunkSize,
    @DefaultValue("2") int executorCorePoolSize,
    @DefaultValue("8") int executorMaxPoolSize,
    @DefaultValue("500") int executorQueueCapacity) {

    public static GroupFanoutProperties defaults() {
        return new GroupFanoutProperties(true, true, 20_000, 200, 300, 2, 8, 500);
    }
}
