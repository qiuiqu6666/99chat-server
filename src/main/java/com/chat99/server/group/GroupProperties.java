package com.chat99.server.group;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.group")
public record GroupProperties(String defaultAvatarUrl) {}
