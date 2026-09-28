package com.chat99.server.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.im.callback.group-message-dedup")
public record GroupMessageDedupProperties(int ttlHours) {

    public GroupMessageDedupProperties {
        if (ttlHours <= 0) {
            ttlHours = 48;
        }
    }
}
