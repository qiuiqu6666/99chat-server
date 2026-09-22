package com.chat99.server.adminapi;

import com.chat99.server.sync.MediaSyncTypes;
import com.chat99.server.sync.SyncEnums;
import com.chat99.server.sync.SyncProperties;
import com.chat99.server.sync.UserPhoto;
import com.chat99.server.sync.UserPhotoRepository;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAlbumsService {

    private static final int ACTIVE = 1;

    private final UserPhotoRepository photoRepository;
    private final AdminUserManagementService users;
    private final SyncProperties syncProps;
    private final AdminAuditService auditService;

    public AdminAlbumsService(UserPhotoRepository photoRepository,
                              AdminUserManagementService users,
                              SyncProperties syncProps,
                              AdminAuditService auditService) {
        this.photoRepository = photoRepository;
        this.users = users;
        this.syncProps = syncProps;
        this.auditService = auditService;
    }

    public AlbumListResponse list(String userUid, String fileType, String keyword,
                                  int page, int pageSize, String sort) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 200);
        String uid = blankToNull(userUid);
        String type = blankToNull(fileType);
        String kw = blankToNull(keyword);

        Specification<UserPhoto> spec = buildSpec(uid, type, kw);
        Page<UserPhoto> result = photoRepository.findAll(
            spec, PageRequest.of(safePage - 1, safeSize, resolveSort(sort)));
        List<AlbumFileItem> files = result.getContent().stream().map(this::toFileItem).toList();
        return new AlbumListResponse(
            uid,
            files,
            result.getTotalElements(),
            safePage,
            safeSize,
            result.hasNext());
    }

    public AlbumDetailResponse detail(String userUid) {
        users.requireUser(userUid);
        Specification<UserPhoto> spec = (root, query, cb) -> cb.and(
            cb.equal(root.get("userId"), userUid),
            cb.equal(root.get("status"), ACTIVE));
        List<UserPhoto> rows = photoRepository.findAll(spec);
        long photoCount = rows.stream().filter(this::isImage).count();
        long videoCount = rows.stream().filter(this::isVideo).count();
        long totalSize = rows.stream().mapToLong(UserPhoto::getSizeBytes).sum();
        java.time.Instant latest = rows.stream()
            .map(UserPhoto::getUpdatedAt)
            .filter(java.util.Objects::nonNull)
            .max(java.time.Instant::compareTo)
            .orElse(null);
        return new AlbumDetailResponse(
            userUid,
            syncProps.photoPrefix(),
            true,
            syncProps.photoPrefix(),
            rows.size(),
            (int) photoCount,
            (int) videoCount,
            totalSize,
            latest == null ? null : latest.getEpochSecond());
    }

    @Transactional
    public AlbumDeleteResponse delete(HttpServletRequest http, String adminUsername, String photoUuid) {
        if (photoUuid == null || photoUuid.isBlank()) {
            throw new AdminApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_error", "id required");
        }
        UserPhoto photo = photoRepository.findByPhotoUuid(photoUuid.trim())
            .filter(p -> p.getStatus() == ACTIVE)
            .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "photo_not_found", "photo_not_found"));
        photo.setStatus(0);
        photoRepository.save(photo);
        auditService.log(http, adminUsername, "album.delete", photo.getUserId(),
            java.util.Map.of("photo_uuid", photo.getPhotoUuid()));
        return new AlbumDeleteResponse(true, photo.getPhotoUuid(), photo.getUserId());
    }

    private AlbumFileItem toFileItem(UserPhoto photo) {
        return new AlbumFileItem(
            photo.getPhotoUuid(),
            photo.getPhotoUuid(),
            photo.getUserId(),
            fileName(photo),
            fileType(photo),
            photo.getMediaType(),
            photo.getSizeBytes(),
            epoch(photo.getTakenAt()),
            epoch(photo.getCreatedAt()),
            epoch(photo.getUpdatedAt()),
            photo.getOriginUrl(),
            photo.getThumbUrl(),
            photo.getPreviewUrl(),
            photo.getStatus() == ACTIVE ? "正常" : "已删除");
    }

    private static String fileName(UserPhoto photo) {
        if (photo.getLocalAssetId() != null && !photo.getLocalAssetId().isBlank()) {
            return photo.getLocalAssetId();
        }
        if (photo.getContentHash() != null && !photo.getContentHash().isBlank()) {
            return photo.getContentHash().substring(0, Math.min(12, photo.getContentHash().length())) + "…";
        }
        return photo.getPhotoUuid();
    }

    private String fileType(UserPhoto photo) {
        if (isVideo(photo)) {
            return "video";
        }
        if (isImage(photo)) {
            return "image";
        }
        return photo.getMimeType();
    }

    private boolean isImage(UserPhoto photo) {
        if (isVideo(photo)) {
            return false;
        }
        String media = photo.getMediaType();
        if (media != null && SyncEnums.MediaType.IMAGE.name().equalsIgnoreCase(media.trim())) {
            return true;
        }
        String mime = lower(photo.getMimeType());
        if (mime.contains("image")) {
            return true;
        }
        String name = lower(fileName(photo));
        return name.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic)$");
    }

    private boolean isVideo(UserPhoto photo) {
        return MediaSyncTypes.isVideo(photo.getMediaType(), photo.getMimeType())
            || lower(fileName(photo)).matches(".*\\.(mp4|mov|m4v|webm|avi|mkv)$");
    }

    private Specification<UserPhoto> buildSpec(String userUid, String fileType, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            preds.add(cb.equal(root.get("status"), ACTIVE));
            if (userUid != null) {
                preds.add(cb.equal(root.get("userId"), userUid));
            }
            if (fileType != null) {
                String ft = fileType.toLowerCase(Locale.ROOT);
                if ("image".equals(ft) || "photo".equals(ft)) {
                    // 全部媒体里排除视频（含 media_type=VIDEO）
                    preds.add(cb.and(
                        cb.or(
                            cb.isNull(root.get("mediaType")),
                            cb.notEqual(cb.upper(root.get("mediaType")), "VIDEO")),
                        cb.notLike(cb.lower(cb.coalesce(root.get("mimeType"), "")), "%video%")));
                } else if ("video".equals(ft)) {
                    preds.add(cb.or(
                        cb.equal(cb.upper(cb.coalesce(root.get("mediaType"), "")), "VIDEO"),
                        cb.like(cb.lower(cb.coalesce(root.get("mimeType"), "")), "%video%"),
                        cb.like(cb.lower(root.get("localAssetId")), "%.mp4"),
                        cb.like(cb.lower(root.get("localAssetId")), "%.mov"),
                        cb.like(cb.lower(root.get("localAssetId")), "%.m4v"),
                        cb.like(cb.lower(root.get("localAssetId")), "%.webm"),
                        cb.like(cb.lower(root.get("localAssetId")), "%.mkv"),
                        cb.like(cb.lower(root.get("localAssetId")), "%.avi")));
                }
            }
            if (keyword != null) {
                String like = "%" + keyword.toLowerCase(Locale.ROOT) + "%";
                preds.add(cb.or(
                    cb.like(cb.lower(root.get("localAssetId")), like),
                    cb.like(cb.lower(root.get("contentHash")), like),
                    cb.like(cb.lower(root.get("photoUuid")), like)));
            }
            return cb.and(preds.toArray(Predicate[]::new));
        };
    }

    private static Sort resolveSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        return switch (sort.trim().toLowerCase(Locale.ROOT)) {
            case "upload_time_asc" -> Sort.by(Sort.Direction.ASC, "createdAt");
            case "shoot_time_desc" -> Sort.by(Sort.Direction.DESC, "takenAt");
            case "shoot_time_asc" -> Sort.by(Sort.Direction.ASC, "takenAt");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    private static Long epoch(java.time.Instant instant) {
        return instant == null ? null : instant.getEpochSecond();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AlbumFileItem(
        String id,
        String albumId,
        String userUid,
        String fileName,
        String fileType,
        String mediaType,
        long fileSize,
        Long shootTime,
        Long uploadTime,
        Long lastModified,
        String ossUrl,
        String ossThumbUrl,
        String previewUrl,
        String storageStatus) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AlbumListResponse(
        String userUid,
        List<AlbumFileItem> files,
        long total,
        int page,
        int pageSize,
        boolean hasMore) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AlbumDetailResponse(
        String userUid,
        String localBaseDir,
        boolean ossEnabled,
        String ossPrefix,
        int totalFiles,
        int photoCount,
        int videoCount,
        long totalSize,
        Long latestSyncAt) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AlbumDeleteResponse(boolean ok, String id, String userUid) {}
}
