package com.chat99.server.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.sync")
public record SyncProperties(
    String photoPrefix,
    int uploadExpireMinutes,
    int maxContactsBatch,
    int maxPhotosCheck,
    int presignExpireSeconds,
    long maxVideoUploadBytes) {

    public SyncProperties {
        if (photoPrefix == null || photoPrefix.isBlank()) {
            photoPrefix = "user-backup/";
        }
        if (!photoPrefix.endsWith("/")) {
            photoPrefix = photoPrefix + "/";
        }
        if (uploadExpireMinutes <= 0) {
            uploadExpireMinutes = 60;
        }
        if (maxContactsBatch <= 0) {
            maxContactsBatch = 500;
        }
        if (maxPhotosCheck <= 0) {
            maxPhotosCheck = 100;
        }
        if (presignExpireSeconds <= 0) {
            presignExpireSeconds = 3600;
        }
        if (maxVideoUploadBytes <= 0) {
            maxVideoUploadBytes = 104_857_600L;
        }
    }
}
