package com.chat99.server.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.admin")
public record AdminProperties(String ipWhitelist) {}
