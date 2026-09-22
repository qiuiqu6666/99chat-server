package com.chat99.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.defaults")
public record AuthProperties(String avatarUrl) {}
