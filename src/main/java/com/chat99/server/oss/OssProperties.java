package com.chat99.server.oss;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.oss")
public record OssProperties(
    long maxUploadBytes,
    int previewLongEdge,
    int thumbSize,
    int jpegQuality,
    String groupAvatarPrefix,
    String userAvatarPrefix,
    String userFavoritePrefix
) {
    public OssProperties {
        if (maxUploadBytes <= 0) maxUploadBytes = 10L * 1024 * 1024;
        if (previewLongEdge <= 0) previewLongEdge = 750;
        if (thumbSize <= 0) thumbSize = 200;
        if (jpegQuality <= 0 || jpegQuality > 100) jpegQuality = 85;
        if (groupAvatarPrefix == null || groupAvatarPrefix.isBlank()) groupAvatarPrefix = "group-avatar/";
        if (userAvatarPrefix == null || userAvatarPrefix.isBlank()) userAvatarPrefix = "user-avatar/";
        if (!userAvatarPrefix.endsWith("/")) userAvatarPrefix = userAvatarPrefix + "/";
        if (userFavoritePrefix == null || userFavoritePrefix.isBlank()) userFavoritePrefix = "user-favorite/";
        if (!userFavoritePrefix.endsWith("/")) userFavoritePrefix = userFavoritePrefix + "/";
    }
}
