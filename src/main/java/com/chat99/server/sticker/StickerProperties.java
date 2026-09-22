package com.chat99.server.sticker;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.sticker")
public record StickerProperties(
    String ossPrefix,
    long staticMaxBytes,
    long gifMaxBytes,
    long videoMaxBytes,
    int thumbSize,
    int thumbJpegQuality,
    int videoOutputSize,
    int videoMaxDurationSeconds,
    int videoMaxFps,
    int videoConversionTimeoutSeconds,
    boolean videoConversionEnabled,
    String ffmpegPath,
    String ffprobePath,
    String userUploadPackId,
    String userUploadName,
    String userUploadIconUrl,
    String favoritesPackId,
    String favoritesPackName,
    int batchMaxSize,
    boolean beforeSendEnabled,
    boolean beforeSendEnforce,
    boolean beforeSendLogOnly,
    List<SystemPack> systemPacks) {

    public StickerProperties {
        if (ossPrefix == null || ossPrefix.isBlank()) {
            ossPrefix = "stickers/";
        }
        if (staticMaxBytes <= 0) {
            staticMaxBytes = 2L * 1024 * 1024;
        }
        if (gifMaxBytes <= 0) {
            gifMaxBytes = 5L * 1024 * 1024;
        }
        if (videoMaxBytes <= 0) {
            videoMaxBytes = 50L * 1024 * 1024;
        }
        if (thumbSize <= 0) {
            thumbSize = 480;
        }
        if (thumbJpegQuality <= 0 || thumbJpegQuality > 100) {
            thumbJpegQuality = 95;
        }
        if (videoOutputSize <= 0) {
            videoOutputSize = 480;
        }
        if (videoMaxDurationSeconds <= 0) {
            videoMaxDurationSeconds = 10;
        }
        if (videoMaxFps <= 0) {
            videoMaxFps = 20;
        }
        if (videoConversionTimeoutSeconds <= 0) {
            videoConversionTimeoutSeconds = 120;
        }
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            ffmpegPath = "/www/server/ffmpeg/ffmpeg-6.1/ffmpeg";
        }
        if (ffprobePath == null || ffprobePath.isBlank()) {
            ffprobePath = "/www/server/ffmpeg/ffmpeg-6.1/ffprobe";
        }
        if (userUploadPackId == null || userUploadPackId.isBlank()) {
            userUploadPackId = "user_upload";
        }
        if (userUploadName == null || userUploadName.isBlank()) {
            userUploadName = "我的上传";
        }
        if (favoritesPackId == null || favoritesPackId.isBlank()) {
            favoritesPackId = "favorites";
        }
        if (favoritesPackName == null || favoritesPackName.isBlank()) {
            favoritesPackName = "收藏";
        }
        if (batchMaxSize <= 0) {
            batchMaxSize = 50;
        }
        if (systemPacks == null || systemPacks.isEmpty()) {
            systemPacks = List.of(
                new SystemPack("4350", "默认一", null, 0),
                new SystemPack("4351", "默认二", null, 1),
                new SystemPack("4352", "默认三", null, 2));
        }
    }

    public record SystemPack(String packId, String name, String iconUrl, int sortOrder) {}
}
