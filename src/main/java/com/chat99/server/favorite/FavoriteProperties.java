package com.chat99.server.favorite;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.favorite")
public record FavoriteProperties(long maxUploadBytes) {

    public FavoriteProperties {
        if (maxUploadBytes <= 0) {
            maxUploadBytes = 100L * 1024 * 1024;
        }
    }
}
