package com.chat99.server.adminapi;

import com.chat99.server.announcement.AnnouncementService;
import com.chat99.server.announcement.AnnouncementType;
import com.chat99.server.im.ImAnnouncementMediaResolver;
import com.chat99.server.im.ImC2cImageContent;
import com.chat99.server.im.ImC2cVideoContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminAnnouncementSendService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;

    private final AnnouncementService announcementService;
    private final AdminAuditService auditService;
    private final ImAnnouncementMediaResolver mediaResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdminAnnouncementSendService(AnnouncementService announcementService,
                                        AdminAuditService auditService,
                                        ImAnnouncementMediaResolver mediaResolver) {
        this.announcementService = announcementService;
        this.auditService = auditService;
        this.mediaResolver = mediaResolver;
    }

    @Transactional
    public SendResult send(HttpServletRequest http, Authentication auth, SendRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        validateSend(req);

        String type = req.contentType().trim().toLowerCase();
        String title;
        String body;
        String payloadJson;
        if ("image".equals(type)) {
            String imageUrl = req.imageUrl().trim();
            title = "[图片]";
            body = imageUrl;
            payloadJson = buildImagePayloadJson(imageUrl, req);
        } else if ("video".equals(type)) {
            String videoUrl = req.videoUrl().trim();
            title = "[视频]";
            body = videoUrl;
            payloadJson = buildVideoPayloadJson(videoUrl, req);
        } else {
            body = req.content().trim();
            title = body.length() > 100 ? body.substring(0, 100) : body;
            payloadJson = buildPayloadJson("text", Map.of());
        }

        List<String> announcementIds = new ArrayList<>();
        Instant scheduledAt = parseScheduledAt(req.scheduledAt());
        boolean scheduled = scheduledAt != null;
        if ("all".equalsIgnoreCase(req.scope())) {
            AnnouncementService.CreateCommand cmd = new AnnouncementService.CreateCommand(
                AnnouncementType.GLOBAL, null, title, body, null, payloadJson,
                0, null, admin.username());
            if (scheduled) {
                AnnouncementService.AnnouncementView view = announcementService.schedule(cmd, scheduledAt);
                announcementIds.add(view.id());
            } else {
                AnnouncementService.AnnouncementView view = announcementService.createDraft(cmd);
                announcementIds.add(view.id());
                announcementService.publish(view.id());
            }
        } else {
            for (String uid : req.userUids()) {
                AnnouncementService.CreateCommand cmd = new AnnouncementService.CreateCommand(
                    AnnouncementType.PERSONAL, uid, title, body, null, payloadJson,
                    0, null, admin.username());
                if (scheduled) {
                    AnnouncementService.AnnouncementView view = announcementService.schedule(cmd, scheduledAt);
                    announcementIds.add(view.id());
                } else {
                    AnnouncementService.AnnouncementView view = announcementService.createDraft(cmd);
                    announcementIds.add(view.id());
                    announcementService.publish(view.id());
                }
            }
        }

        auditService.log(http, admin.username(), scheduled ? "announcement.schedule" : "announcement.send", null,
            Map.of(
                "content_type", type,
                "scope", req.scope(),
                "count", announcementIds.size(),
                "announcement_ids", announcementIds,
                "scheduled_at", scheduled ? scheduledAt.toString() : ""));

        return new SendResult(
            true,
            type,
            req.scope(),
            announcementIds.size(),
            announcementIds,
            !scheduled && "all".equalsIgnoreCase(req.scope()),
            scheduled,
            scheduled ? scheduledAt.toString() : null);
    }

    public ImageUploadResult uploadImage(Authentication auth, MultipartFile file) {
        AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        validateUploadFile(file, IMAGE_TYPES, MAX_IMAGE_BYTES);
        try {
            ImAnnouncementMediaResolver.ImageUploadBundle bundle =
                mediaResolver.uploadImageVariants(file.getBytes());
            return new ImageUploadResult(
                true,
                bundle.originUrl(),
                bundle.previewUrl(),
                bundle.thumbUrl(),
                bundle.width(),
                bundle.height(),
                bundle.originSize(),
                bundle.previewSize(),
                bundle.thumbSize(),
                bundle.thumbWidth(),
                bundle.thumbHeight(),
                bundle.objectKey());
        } catch (Exception e) {
            throw new AdminApiException(HttpStatus.INTERNAL_SERVER_ERROR, "upload_failed", "upload_failed");
        }
    }

    public VideoUploadResult uploadVideo(Authentication auth, MultipartFile file) {
        AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        validateUploadFile(file, VIDEO_TYPES, MAX_VIDEO_BYTES);
        String contentType = file.getContentType() == null ? "video/mp4" : file.getContentType().trim();
        try {
            ImAnnouncementMediaResolver.VideoUploadBundle bundle =
                mediaResolver.uploadVideoWithThumb(file.getBytes(), videoExt(file), contentType);
            return new VideoUploadResult(
                true,
                bundle.videoUrl(),
                bundle.thumbUrl(),
                bundle.videoSize(),
                bundle.objectKey());
        } catch (Exception e) {
            throw new AdminApiException(HttpStatus.INTERNAL_SERVER_ERROR, "upload_failed", "upload_failed");
        }
    }

    private String buildImagePayloadJson(String imageUrl, SendRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentType", "image");
        payload.put("imageUrl", imageUrl);
        putIfPresent(payload, "previewUrl", req.previewUrl());
        putIfPresent(payload, "thumbUrl", req.thumbUrl());
        putIfPositive(payload, "width", req.width());
        putIfPositive(payload, "height", req.height());
        putIfPositive(payload, "imageSize", req.imageSize());
        putIfPositive(payload, "previewSize", req.previewSize());
        putIfPositive(payload, "thumbSize", req.thumbSize());
        putIfPositive(payload, "thumbWidth", req.thumbWidth());
        putIfPositive(payload, "thumbHeight", req.thumbHeight());
        ImC2cImageContent resolved = mediaResolver.resolveImage(payload, imageUrl);
        payload.put("imageUrl", resolved.originUrl());
        payload.put("previewUrl", resolved.largeUrl());
        payload.put("thumbUrl", resolved.thumbUrl());
        payload.put("width", resolved.width());
        payload.put("height", resolved.height());
        payload.put("imageSize", resolved.originSize());
        payload.put("previewSize", resolved.largeSize());
        payload.put("thumbSize", resolved.thumbSize());
        payload.put("thumbWidth", resolved.thumbWidth());
        payload.put("thumbHeight", resolved.thumbHeight());
        return toJson(payload);
    }

    private String buildVideoPayloadJson(String videoUrl, SendRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentType", "video");
        payload.put("videoUrl", videoUrl);
        putIfPresent(payload, "thumbUrl", req.thumbUrl());
        putIfPositive(payload, "videoSize", req.videoSize());
        putIfPositive(payload, "videoSecond", req.videoSecond());
        putIfPositive(payload, "thumbSize", req.thumbSize());
        putIfPositive(payload, "thumbWidth", req.thumbWidth());
        putIfPositive(payload, "thumbHeight", req.thumbHeight());
        ImC2cVideoContent resolved = mediaResolver.resolveVideo(payload, videoUrl);
        payload.put("videoUrl", resolved.videoUrl());
        payload.put("thumbUrl", resolved.thumbUrl());
        payload.put("videoSize", resolved.videoSize());
        payload.put("videoSecond", resolved.videoSecond());
        payload.put("thumbSize", resolved.thumbSize());
        payload.put("thumbWidth", resolved.thumbWidth());
        payload.put("thumbHeight", resolved.thumbHeight());
        return toJson(payload);
    }

    private void validateUploadFile(MultipartFile file, Set<String> allowedTypes, long maxBytes) {
        if (file == null || file.isEmpty()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "file required");
        }
        if (file.getSize() > maxBytes) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "file too large");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase();
        if (!allowedTypes.contains(contentType)) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid file type");
        }
    }

    private static String videoExt(MultipartFile file) {
        String contentType = file.getContentType() == null ? "" : file.getContentType().trim().toLowerCase();
        return switch (contentType) {
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            default -> "mp4";
        };
    }

    private void validateSend(SendRequest req) {
        String type = req.contentType() == null ? "" : req.contentType().trim().toLowerCase();
        if ("text".equals(type)) {
            if (req.content() == null || req.content().isBlank()) {
                throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "content required");
            }
            if (req.content().trim().length() > 4000) {
                throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "content too long");
            }
        } else if ("image".equals(type)) {
            validateHttpUrl(req.imageUrl(), "image_url");
        } else if ("video".equals(type)) {
            validateHttpUrl(req.videoUrl(), "video_url");
            if (req.thumbUrl() != null && !req.thumbUrl().isBlank()) {
                validateHttpUrl(req.thumbUrl(), "thumb_url");
            }
        } else {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid content_type");
        }
        Instant scheduledAt = parseScheduledAt(req.scheduledAt());
        if (scheduledAt != null && !scheduledAt.isAfter(Instant.now().plusSeconds(30))) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "scheduled_at too soon");
        }
        String scope = req.scope() == null ? "" : req.scope().trim().toLowerCase();
        if ("all".equals(scope)) {
            return;
        }
        if ("uids".equals(scope)) {
            if (req.userUids() == null || req.userUids().isEmpty()) {
                throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "user_uids required");
            }
            if (req.userUids().size() > 500) {
                throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "too many user_uids");
            }
            return;
        }
        throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid scope");
    }

    private static Instant parseScheduledAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid scheduled_at");
        }
    }

    private static void validateHttpUrl(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", field + " required");
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid " + field);
            }
        } catch (IllegalArgumentException e) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "invalid " + field);
        }
    }

    private String buildPayloadJson(String contentType, Map<String, ?> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentType", contentType);
        payload.putAll(extra);
        return toJson(payload);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new AdminApiException(HttpStatus.INTERNAL_SERVER_ERROR, "payload_error", "payload_error");
        }
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value.trim());
        }
    }

    private static void putIfPositive(Map<String, Object> payload, String key, Long value) {
        if (value != null && value > 0) {
            payload.put(key, value);
        }
    }

    private static void putIfPositive(Map<String, Object> payload, String key, Integer value) {
        if (value != null && value > 0) {
            payload.put(key, value);
        }
    }

    public record SendRequest(
        String contentType,
        String content,
        String imageUrl,
        String previewUrl,
        String thumbUrl,
        Integer width,
        Integer height,
        Long imageSize,
        Long previewSize,
        Long thumbSize,
        Integer thumbWidth,
        Integer thumbHeight,
        String videoUrl,
        Long videoSize,
        Integer videoSecond,
        String scope,
        List<String> userUids,
        String scheduledAt) {}

    public record SendResult(
        boolean ok,
        String contentType,
        String scope,
        int sentCount,
        List<String> announcementIds,
        boolean imPushQueued,
        boolean scheduled,
        String scheduledAt) {}

    public record ImageUploadResult(
        boolean ok,
        String imageUrl,
        String previewUrl,
        String thumbUrl,
        int width,
        int height,
        long imageSize,
        long previewSize,
        long thumbSize,
        int thumbWidth,
        int thumbHeight,
        String objectKey) {}

    public record VideoUploadResult(
        boolean ok,
        String videoUrl,
        String thumbUrl,
        long videoSize,
        String objectKey) {}
}
