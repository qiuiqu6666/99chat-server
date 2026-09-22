package com.chat99.server.adminapi;

import com.chat99.server.announcement.Announcement;
import com.chat99.server.announcement.AnnouncementImPushStatus;
import com.chat99.server.announcement.AnnouncementRepository;
import com.chat99.server.announcement.AnnouncementStatus;
import com.chat99.server.announcement.AnnouncementType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class AdminAnnouncementListService {

    private static final int SUMMARY_MAX = 120;

    private final AnnouncementRepository announcementRepository;
    private final ObjectMapper json = new ObjectMapper();

    public AdminAnnouncementListService(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    public AnnouncementListResponse list(String keyword,
                                         String contentType,
                                         String scopeType,
                                         String targetUserId,
                                         String imPushStatus,
                                         String status,
                                         String createdBy,
                                         int page,
                                         int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        Specification<Announcement> spec = buildSpec(
            blankToNull(keyword),
            blankToNull(contentType),
            blankToNull(scopeType),
            blankToNull(targetUserId),
            blankToNull(imPushStatus),
            blankToNull(status),
            blankToNull(createdBy));
        Page<Announcement> result = announcementRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<AnnouncementItem> items = result.getContent().stream()
            .map(this::toItem)
            .toList();
        return new AnnouncementListResponse(items, result.getTotalElements(), safePage, safeSize, result.hasNext());
    }

    private AnnouncementItem toItem(Announcement row) {
        Map<String, Object> payload = parsePayload(row.getPayloadJson());
        String resolvedContentType = resolveContentType(row, payload);
        MediaPreview media = resolveMedia(resolvedContentType, row, payload);
        AnnouncementType type = row.getType() == null ? AnnouncementType.GLOBAL : row.getType();
        AnnouncementStatus publishStatus = row.getStatus() == null ? AnnouncementStatus.DRAFT : row.getStatus();
        ImPushView pushView = resolveImPush(row, type, publishStatus);
        return new AnnouncementItem(
            row.getId(),
            resolvedContentType,
            contentTypeLabel(resolvedContentType),
            summarize(row.getBody(), resolvedContentType),
            media.previewUrl(),
            media.thumbUrl(),
            media.mediaUrl(),
            type.name().toLowerCase(Locale.ROOT),
            scopeLabel(type),
            row.getTargetUserId(),
            publishStatus.name().toLowerCase(Locale.ROOT),
            statusLabel(publishStatus),
            pushView.code(),
            pushView.label(),
            row.getCreatedBy(),
            toIso(row.getPublishAt()),
            toIso(row.getCreatedAt()),
            toIso(row.getUpdatedAt()));
    }

    private static ImPushView resolveImPush(Announcement row, AnnouncementType type, AnnouncementStatus status) {
        if (status == AnnouncementStatus.SCHEDULED) {
            return new ImPushView("scheduled", "定时待发");
        }
        if (type == AnnouncementType.PERSONAL) {
            if (status == AnnouncementStatus.PUBLISHED) {
                return new ImPushView("done", "已推送");
            }
            return new ImPushView("pending", "待推送");
        }
        AnnouncementImPushStatus pushStatus = row.getImPushStatus();
        if (pushStatus == null) {
            return new ImPushView("pending", "待推送");
        }
        return switch (pushStatus) {
            case PENDING -> new ImPushView("pending", "排队中");
            case RUNNING -> new ImPushView("running", "推送中");
            case DONE -> new ImPushView("done", "已完成");
            case SKIPPED -> new ImPushView("skipped", "未启用全站推送");
        };
    }

    private Map<String, Object> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = json.readValue(raw, new TypeReference<>() {});
            return payload == null ? Map.of() : payload;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String resolveContentType(Announcement row, Map<String, Object> payload) {
        String fromPayload = stringValue(payload.get("contentType"));
        if (fromPayload != null) {
            return fromPayload.toLowerCase(Locale.ROOT);
        }
        if ("[图片]".equals(row.getTitle())) {
            return "image";
        }
        if ("[视频]".equals(row.getTitle())) {
            return "video";
        }
        return "text";
    }

    private MediaPreview resolveMedia(String contentType, Announcement row, Map<String, Object> payload) {
        if ("image".equals(contentType)) {
            String preview = firstNonBlank(
                stringValue(payload.get("previewUrl")),
                stringValue(payload.get("thumbUrl")),
                stringValue(payload.get("imageUrl")),
                row.getBody());
            String thumb = firstNonBlank(
                stringValue(payload.get("thumbUrl")),
                stringValue(payload.get("previewUrl")),
                stringValue(payload.get("imageUrl")),
                row.getBody());
            String image = firstNonBlank(stringValue(payload.get("imageUrl")), row.getBody());
            return new MediaPreview(preview, thumb, image);
        }
        if ("video".equals(contentType)) {
            String video = firstNonBlank(stringValue(payload.get("videoUrl")), row.getBody());
            String thumb = firstNonBlank(stringValue(payload.get("thumbUrl")), video);
            return new MediaPreview(thumb, thumb, video);
        }
        return new MediaPreview(null, null, null);
    }

    private static String summarize(String body, String contentType) {
        if ("image".equals(contentType)) {
            return "[图片消息]";
        }
        if ("video".equals(contentType)) {
            return "[视频消息]";
        }
        if (body == null || body.isBlank()) {
            return "—";
        }
        String text = body.trim();
        return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX) + "…";
    }

    private static String contentTypeLabel(String contentType) {
        return switch (contentType) {
            case "image" -> "图片";
            case "video" -> "视频";
            default -> "文本";
        };
    }

    private static String scopeLabel(AnnouncementType type) {
        return type == AnnouncementType.PERSONAL ? "指定用户" : "全站";
    }

    private static String statusLabel(AnnouncementStatus status) {
        return switch (status) {
            case DRAFT -> "草稿";
            case SCHEDULED -> "定时待发";
            case PUBLISHED -> "已发布";
            case REVOKED -> "已撤回";
        };
    }

    private Specification<Announcement> buildSpec(String keyword,
                                                  String contentType,
                                                  String scopeType,
                                                  String targetUserId,
                                                  String imPushStatus,
                                                  String statusFilter,
                                                  String createdBy) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            AnnouncementType scope = parseScopeType(scopeType);
            if (scope != null) {
                preds.add(cb.equal(root.get("type"), scope));
            }
            if (targetUserId != null) {
                preds.add(cb.equal(root.get("targetUserId"), targetUserId));
            }
            if (createdBy != null) {
                preds.add(cb.equal(root.get("createdBy"), createdBy));
            }
            AnnouncementStatus status = parseStatus(statusFilter);
            if (status != null) {
                preds.add(cb.equal(root.get("status"), status));
            }
            AnnouncementImPushStatus pushStatus = parseImPushStatus(imPushStatus);
            if (pushStatus != null) {
                preds.add(cb.equal(root.get("imPushStatus"), pushStatus));
            }
            if (contentType != null) {
                String ct = contentType.toLowerCase(Locale.ROOT);
                if ("image".equals(ct)) {
                    preds.add(cb.or(
                        cb.equal(root.get("title"), "[图片]"),
                        cb.like(cb.lower(root.get("payloadJson")), "%\"contenttype\":\"image\"%")));
                } else if ("video".equals(ct)) {
                    preds.add(cb.or(
                        cb.equal(root.get("title"), "[视频]"),
                        cb.like(cb.lower(root.get("payloadJson")), "%\"contenttype\":\"video\"%")));
                } else if ("text".equals(ct)) {
                    preds.add(cb.and(
                        cb.notEqual(root.get("title"), "[图片]"),
                        cb.notEqual(root.get("title"), "[视频]"),
                        cb.or(
                            cb.isNull(root.get("payloadJson")),
                            cb.and(
                                cb.notLike(cb.lower(root.get("payloadJson")), "%\"contenttype\":\"image\"%"),
                                cb.notLike(cb.lower(root.get("payloadJson")), "%\"contenttype\":\"video\"%")))));
                }
            }
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("id")), like),
                    cb.like(cb.lower(root.get("title")), like),
                    cb.like(cb.lower(root.get("body")), like),
                    cb.like(cb.lower(root.get("createdBy")), like),
                    cb.like(cb.lower(root.get("targetUserId")), like)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static AnnouncementType parseScopeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "global", "all" -> AnnouncementType.GLOBAL;
            case "personal", "uids", "user" -> AnnouncementType.PERSONAL;
            default -> null;
        };
    }

    private static AnnouncementStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AnnouncementStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AnnouncementImPushStatus parseImPushStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AnnouncementImPushStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String toIso(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private record MediaPreview(String previewUrl, String thumbUrl, String mediaUrl) {}

    private record ImPushView(String code, String label) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnnouncementItem(
        String id,
        String contentType,
        String contentTypeLabel,
        String contentSummary,
        String previewUrl,
        String thumbUrl,
        String mediaUrl,
        String scopeType,
        String scopeLabel,
        String targetUserId,
        String status,
        String statusLabel,
        String imPushStatus,
        String imPushStatusLabel,
        String createdBy,
        String publishAt,
        String createdAt,
        String updatedAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnnouncementListResponse(
        List<AnnouncementItem> items,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}
}
