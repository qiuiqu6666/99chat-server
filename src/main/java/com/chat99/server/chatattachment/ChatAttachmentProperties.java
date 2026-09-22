package com.chat99.server.chatattachment;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat99.chat-attachment")
public record ChatAttachmentProperties(
    int policyVersion,
    boolean uploadEnabled,
    boolean sendEnabled,
    boolean readEnabled,
    boolean emergencyDisabled,
    long routingThresholdBytes,
    NativeMax nativeMaxBytes,
    long maxAttachmentBytes,
    long userStorageQuotaBytes,
    long dailyUploadQuotaBytes,
    String quotaDayTimezone,
    int maxActiveUploadsPerUser,
    long partSizeBytes,
    int maxParallelPartsPerUpload,
    int uploadSessionTtlSeconds,
    int uploadPartUrlTtlSeconds,
    int accessUrlTtlSeconds,
    int confirmedRetentionDays,
    int unreferencedReadyTtlSeconds,
    int reservedReferenceTtlSeconds,
    int deleteGraceSeconds,
    long thumbnailMaxBytes,
    int thumbnailMaxLongEdge,
    int partUrlBatchLimit,
    List<String> supportedPlatforms,
    String minReceiverVersion,
    int minReceiverBuild,
    int requiredProtocolVersion,
    boolean nativeVideoMessageEnabled,
    String mediaPublicBaseUrl,
    String mediaHmacSecret,
    Oss oss,
    Gray gray,
    RateLimit rateLimit,
    Jobs jobs,
    String ffprobePath,
    int mediaProbeTimeoutSeconds
) {
    public ChatAttachmentProperties {
        if (policyVersion <= 0) {
            policyVersion = 1;
        }
        if (routingThresholdBytes <= 0) {
            routingThresholdBytes = 104_857_600L;
        }
        if (nativeMaxBytes == null) {
            nativeMaxBytes = new NativeMax(0, 0, 0, 0);
        }
        if (maxAttachmentBytes <= 0) {
            maxAttachmentBytes = 2_147_483_648L;
        }
        if (userStorageQuotaBytes <= 0) {
            userStorageQuotaBytes = 21_474_836_480L;
        }
        if (dailyUploadQuotaBytes <= 0) {
            dailyUploadQuotaBytes = 10_737_418_240L;
        }
        if (quotaDayTimezone == null || quotaDayTimezone.isBlank()) {
            quotaDayTimezone = "Asia/Shanghai";
        }
        if (maxActiveUploadsPerUser <= 0) {
            maxActiveUploadsPerUser = 3;
        }
        if (partSizeBytes <= 0) {
            partSizeBytes = 8_388_608L;
        }
        if (maxParallelPartsPerUpload <= 0) {
            maxParallelPartsPerUpload = 2;
        }
        if (uploadSessionTtlSeconds <= 0) {
            uploadSessionTtlSeconds = 86_400;
        }
        if (uploadPartUrlTtlSeconds <= 0) {
            uploadPartUrlTtlSeconds = 900;
        }
        if (accessUrlTtlSeconds <= 0) {
            accessUrlTtlSeconds = 900;
        }
        if (confirmedRetentionDays <= 0) {
            confirmedRetentionDays = 30;
        }
        if (unreferencedReadyTtlSeconds <= 0) {
            unreferencedReadyTtlSeconds = 259_200;
        }
        if (reservedReferenceTtlSeconds <= 0) {
            reservedReferenceTtlSeconds = 604_800;
        }
        if (deleteGraceSeconds <= 0) {
            deleteGraceSeconds = 604_800;
        }
        if (thumbnailMaxBytes <= 0) {
            thumbnailMaxBytes = 1_048_576L;
        }
        if (thumbnailMaxLongEdge <= 0) {
            thumbnailMaxLongEdge = 1280;
        }
        if (partUrlBatchLimit <= 0) {
            partUrlBatchLimit = 32;
        }
        if (supportedPlatforms == null || supportedPlatforms.isEmpty()) {
            supportedPlatforms = List.of("android", "ios");
        } else {
            supportedPlatforms = supportedPlatforms.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase())
                .toList();
        }
        if (minReceiverVersion == null || minReceiverVersion.isBlank()) {
            minReceiverVersion = "0.0.0";
        }
        if (minReceiverBuild < 0) {
            minReceiverBuild = 0;
        }
        if (requiredProtocolVersion <= 0) {
            requiredProtocolVersion = 1;
        }
        if (mediaPublicBaseUrl == null || mediaPublicBaseUrl.isBlank()) {
            mediaPublicBaseUrl = "https://image.99chat.vip";
        } else {
            mediaPublicBaseUrl = mediaPublicBaseUrl.trim().replaceAll("/+$", "");
            if (!mediaPublicBaseUrl.contains("://")) {
                mediaPublicBaseUrl = "https://" + mediaPublicBaseUrl;
            }
        }
        if (mediaHmacSecret != null && mediaHmacSecret.isBlank()) {
            mediaHmacSecret = null;
        }
        if (oss == null) {
            oss = new Oss(null, null, null, null, null);
        }
        if (gray == null) {
            gray = new Gray(List.of(), List.of(), false);
        }
        if (rateLimit == null) {
            rateLimit = new RateLimit(10, 30, 60, 20, 60);
        }
        if (jobs == null) {
            jobs = new Jobs(300_000, "0 20 4 * * ?", 600_000);
        }
        if (ffprobePath == null || ffprobePath.isBlank()) {
            ffprobePath = "/www/server/ffmpeg/ffmpeg-6.1/ffprobe";
        }
        if (mediaProbeTimeoutSeconds <= 0) {
            mediaProbeTimeoutSeconds = 10;
        }
    }

    public record NativeMax(long image, long sound, long video, long file) {
        public NativeMax {
            if (image <= 0) {
                image = 29_360_128L;
            }
            if (sound <= 0) {
                sound = 29_360_128L;
            }
            if (video <= 0) {
                video = 104_857_600L;
            }
            if (file <= 0) {
                file = 104_857_600L;
            }
        }
    }

    public record Oss(
        String endpoint,
        String bucket,
        String accessKeyId,
        String accessKeySecret,
        String cdnDomain
    ) {
        public Oss {
            endpoint = blankToNull(endpoint);
            bucket = blankToNull(bucket);
            accessKeyId = blankToNull(accessKeyId);
            accessKeySecret = blankToNull(accessKeySecret);
            cdnDomain = blankToNull(cdnDomain);
        }

        private static String blankToNull(String v) {
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    public record Gray(
        List<String> sendAllowUserIds,
        List<String> sendAllowGroupIds,
        boolean requirePeerCapability
    ) {
        public Gray {
            sendAllowUserIds = clean(sendAllowUserIds);
            sendAllowGroupIds = clean(sendAllowGroupIds);
        }

        /** 名单为空表示不额外限制；非空才按白名单拦截。 */
        public boolean senderAllowed(String userId) {
            return sendAllowUserIds.isEmpty() || sendAllowUserIds.contains(userId);
        }

        public boolean groupAllowed(String groupId) {
            return sendAllowGroupIds.isEmpty() || sendAllowGroupIds.contains(groupId);
        }

        private static List<String> clean(List<String> in) {
            if (in == null || in.isEmpty()) {
                return List.of();
            }
            return in.stream()
                .flatMap(v -> v == null ? java.util.stream.Stream.empty() : Arrays.stream(v.split(",")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }
    }

    public record RateLimit(
        int initPerMinute,
        int partUrlsPerMinute,
        int statusPerMinute,
        int completePerMinute,
        int accessPerMinute
    ) {
        public RateLimit {
            if (initPerMinute <= 0) {
                initPerMinute = 10;
            }
            if (partUrlsPerMinute < 0) {
                partUrlsPerMinute = 30;
            }
            if (statusPerMinute <= 0) {
                statusPerMinute = 60;
            }
            if (completePerMinute <= 0) {
                completePerMinute = 20;
            }
            if (accessPerMinute <= 0) {
                accessPerMinute = 60;
            }
        }
    }

    public record Jobs(
        long expireFixedDelayMs,
        String cleanupCron,
        long reconcileFixedDelayMs
    ) {
        public Jobs {
            if (expireFixedDelayMs <= 0) {
                expireFixedDelayMs = 300_000;
            }
            if (cleanupCron == null || cleanupCron.isBlank()) {
                cleanupCron = "0 20 4 * * ?";
            }
            if (reconcileFixedDelayMs <= 0) {
                reconcileFixedDelayMs = 600_000;
            }
        }
    }
}
