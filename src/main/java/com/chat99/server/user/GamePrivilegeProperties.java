package com.chat99.server.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.game-privilege")
public record GamePrivilegeProperties(
    boolean masterEnabled,
    boolean defaultPrivileged) {

    public GamePrivilegeProperties {
        // masterEnabled: 全局总开关；defaultPrivileged: 新用户默认是否特权
    }
}
