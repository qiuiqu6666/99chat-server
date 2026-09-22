package com.chat99.server.favorite;

import com.chat99.server.oss.OssClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MessageFavoriteService {

    public static final int MAX_VIDEO_SEC = 60;
    public static final int MAX_PAGE_SIZE = 100;

    private final UserMessageFavoriteRepository repository;
    private final FavoriteOssService ossService;
    private final OssClient ossClient;
    private final ObjectMapper json = new ObjectMapper();

    public MessageFavoriteService(UserMessageFavoriteRepository repository,
                                  FavoriteOssService ossService,
                                  OssClient ossClient) {
        this.repository = repository;
        this.ossService = ossService;
        this.ossClient = ossClient;
    }

    public record FavoriteItemView(
        String id,
        String type,
        String text,
        String thumbUrl,
        String mediaUrl,
        Integer durationSec,
        Integer width,
        Integer height,
        String sourceSenderName,
        String sourceConvLabel,
        String sourceMsgId,
        String sourceConvId,
        boolean isManual,
        Instant favoritedAt,
        Instant updatedAt) {}

    public record FavoriteListResponse(
        List<FavoriteItemView> items,
        long total,
        int page,
        int size) {}

    public record BatchDeleteResponse(int deleted) {}

    public record CreateFavoriteRequest(
        FavoriteType type,
        String text,
        String remoteMediaUrl,
        String remoteThumbUrl,
        Integer durationSec,
        String sourceMsgId,
        String sourceConvId,
        String sourceSenderName,
        String sourceConvLabel,
        String remark) {}

    public record UpdateFavoriteRequest(String text, String sourceConvLabel) {}

    @Transactional(readOnly = true)
    public FavoriteListResponse list(String userId, FavoriteType type, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        PageRequest pageable = PageRequest.of(safePage, safeSize);
        Page<UserMessageFavorite> result = type == null
            ? repository.findByUserIdOrderByFavoritedAtDesc(userId, pageable)
            : repository.findByUserIdAndTypeOrderByFavoritedAtDesc(userId, type, pageable);
        return new FavoriteListResponse(
            result.getContent().stream().map(this::toView).toList(),
            result.getTotalElements(),
            safePage,
            safeSize);
    }

    @Transactional(readOnly = true)
    public FavoriteItemView get(String userId, String id) {
        return toView(requireOwned(userId, id));
    }

    @Transactional
    public FavoriteItemView create(String userId, CreateFavoriteRequest req) throws IOException {
        req = normalizeCreate(req);
        if (req.sourceMsgId() != null && !req.sourceMsgId().isBlank()) {
            var existing = repository.findByUserIdAndSourceMsgId(userId, req.sourceMsgId().trim());
            if (existing.isPresent()) {
                return toView(existing.get());
            }
        }
        UserMessageFavorite row = new UserMessageFavorite();
        row.setId(UUID.randomUUID().toString());
        row.setUserId(userId);
        row.setType(req.type());
        row.setManual(req.sourceMsgId() == null || req.sourceMsgId().isBlank());
        applySourceFields(row, req.sourceMsgId(), req.sourceConvId(), req.sourceSenderName(),
            firstNonBlank(req.sourceConvLabel(), req.remark()));

        switch (req.type()) {
            case TEXT -> {
                if (req.text() == null || req.text().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
                }
                row.setText(req.text().trim());
            }
            case IMAGE -> {
                if (req.remoteMediaUrl() == null || req.remoteMediaUrl().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
                }
                FavoriteOssService.StoredMedia media = ossService.storeImageFromRemote(
                    userId, req.remoteMediaUrl(), req.remoteThumbUrl());
                applyMedia(row, media, null);
                if (req.text() != null && !req.text().isBlank()) {
                    row.setText(req.text().trim());
                }
            }
            case VIDEO -> {
                validateVideoDuration(req.durationSec());
                if (req.remoteMediaUrl() == null || req.remoteMediaUrl().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
                }
                FavoriteOssService.StoredMedia media = ossService.storeVideoFromRemote(
                    userId, req.remoteMediaUrl(), req.remoteThumbUrl());
                applyMedia(row, media, req.durationSec());
            }
        }
        repository.save(row);
        return toView(row);
    }

    @Transactional
    public FavoriteItemView upload(String userId, MultipartFile file, MultipartFile snapshot,
                                   String metadataJson) throws IOException {
        FavoriteUploadMetadata meta = parseMetadata(metadataJson);
        FavoriteType type = resolveUploadType(meta, file);
        if (type == null) {
            throw invalidInput("metadata.type or inferrable file/text required");
        }
        if (type == FavoriteType.TEXT) {
            if (meta.text() == null || meta.text().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
            }
            return create(userId, new CreateFavoriteRequest(
                FavoriteType.TEXT, meta.text(), null, null, null,
                null, null, null, meta.sourceConvLabel(), null));
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }
        UserMessageFavorite row = new UserMessageFavorite();
        row.setId(UUID.randomUUID().toString());
        row.setUserId(userId);
        row.setType(type);
        row.setManual(true);
        if (meta.sourceConvLabel() != null && !meta.sourceConvLabel().isBlank()) {
            row.setSourceConvLabel(meta.sourceConvLabel().trim());
        }
        switch (type) {
            case IMAGE -> {
                byte[] raw = ossService.readImageFile(file);
                FavoriteOssService.StoredMedia media = ossService.storeImageBytes(userId, raw);
                applyMedia(row, media, null);
            }
            case VIDEO -> {
                validateVideoDuration(meta.durationSec());
                byte[] video = ossService.readVideoFile(file);
                byte[] thumb = ossService.readOptionalImage(snapshot);
                FavoriteOssService.StoredMedia media = ossService.storeVideoBytes(userId, video, thumb);
                applyMedia(row, media, meta.durationSec());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        repository.save(row);
        return toView(row);
    }

    @Transactional
    public FavoriteItemView update(String userId, String id, UpdateFavoriteRequest req) {
        UserMessageFavorite row = requireOwned(userId, id);
        if (req.text() != null) {
            if (row.getType() != FavoriteType.TEXT) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            if (req.text().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_CONTENT");
            }
            row.setText(req.text().trim());
        }
        if (req.sourceConvLabel() != null) {
            row.setSourceConvLabel(req.sourceConvLabel().isBlank() ? null : req.sourceConvLabel().trim());
        }
        repository.save(row);
        return toView(row);
    }

    @Transactional
    public void delete(String userId, String id) {
        UserMessageFavorite row = requireOwned(userId, id);
        deleteOss(row);
        repository.delete(row);
    }

    @Transactional
    public BatchDeleteResponse deleteBatch(String userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BatchDeleteResponse(0);
        }
        List<UserMessageFavorite> rows = repository.findByUserIdAndIdIn(userId, ids);
        for (UserMessageFavorite row : rows) {
            deleteOss(row);
        }
        repository.deleteAll(rows);
        return new BatchDeleteResponse(rows.size());
    }

    private UserMessageFavorite requireOwned(String userId, String id) {
        return repository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FAVORITE_NOT_FOUND"));
    }

    private void applySourceFields(UserMessageFavorite row, String sourceMsgId, String sourceConvId,
                                   String sourceSenderName, String sourceConvLabel) {
        row.setSourceMsgId(blankToNull(sourceMsgId));
        row.setSourceConvId(blankToNull(sourceConvId));
        row.setSourceSenderName(blankToNull(sourceSenderName));
        row.setSourceConvLabel(blankToNull(sourceConvLabel));
    }

    private void applyMedia(UserMessageFavorite row, FavoriteOssService.StoredMedia media, Integer durationSec) {
        row.setThumbUrl(media.thumbUrl());
        row.setMediaUrl(media.mediaUrl());
        row.setThumbObjectKey(media.thumbObjectKey());
        row.setMediaObjectKey(media.mediaObjectKey());
        row.setWidth(media.width());
        row.setHeight(media.height());
        row.setDurationSec(durationSec);
    }

    private void validateVideoDuration(Integer durationSec) {
        if (durationSec != null && durationSec > MAX_VIDEO_SEC) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VIDEO_TOO_LONG");
        }
    }

    private void deleteOss(UserMessageFavorite row) {
        ossClient.deleteObject(row.getThumbObjectKey());
        ossClient.deleteObject(row.getMediaObjectKey());
    }

    private CreateFavoriteRequest normalizeCreate(CreateFavoriteRequest req) {
        FavoriteType type = req.type();
        if (type == null) {
            type = FavoriteType.inferFromPayload(req.text(), req.remoteMediaUrl(), req.durationSec());
        }
        if (type == null) {
            throw invalidInput("type is required (or provide text / remoteMediaUrl)");
        }
        if (type == req.type()) {
            return req;
        }
        return new CreateFavoriteRequest(
            type, req.text(), req.remoteMediaUrl(), req.remoteThumbUrl(), req.durationSec(),
            req.sourceMsgId(), req.sourceConvId(), req.sourceSenderName(), req.sourceConvLabel(), req.remark());
    }

    private FavoriteType resolveUploadType(FavoriteUploadMetadata meta, MultipartFile file) {
        if (meta.type() != null) {
            return meta.type();
        }
        if (meta.elemType() != null) {
            FavoriteType fromIm = FavoriteType.fromImElemType(meta.elemType());
            if (fromIm != null) {
                return fromIm;
            }
        }
        if (meta.text() != null && !meta.text().isBlank() && (file == null || file.isEmpty())) {
            return FavoriteType.TEXT;
        }
        if (file != null && !file.isEmpty()) {
            return inferTypeFromFile(file);
        }
        return null;
    }

    private static FavoriteType inferTypeFromFile(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.startsWith("image/")) {
                return FavoriteType.IMAGE;
            }
            if (ct.startsWith("video/")) {
                return FavoriteType.VIDEO;
            }
        }
        String filename = file.getOriginalFilename();
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) {
                String ext = filename.substring(dot + 1).toLowerCase();
                if (Set.of("jpg", "jpeg", "png", "webp", "gif", "heic", "heif").contains(ext)) {
                    return FavoriteType.IMAGE;
                }
                if (Set.of("mp4", "mov", "m4v").contains(ext)) {
                    return FavoriteType.VIDEO;
                }
            }
        }
        return null;
    }

    private FavoriteUploadMetadata parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new FavoriteUploadMetadata(null, null, null, null, null);
        }
        try {
            return json.readValue(metadataJson, FavoriteUploadMetadata.class);
        } catch (Exception e) {
            throw invalidInput("metadata must be valid JSON");
        }
    }

    private static ResponseStatusException invalidInput(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
    }

    private FavoriteItemView toView(UserMessageFavorite row) {
        return new FavoriteItemView(
            row.getId(),
            row.getType().name(),
            row.getText(),
            row.getThumbUrl(),
            row.getMediaUrl(),
            row.getDurationSec(),
            row.getWidth(),
            row.getHeight(),
            row.getSourceSenderName(),
            row.getSourceConvLabel(),
            row.getSourceMsgId(),
            row.getSourceConvId(),
            row.isManual(),
            row.getFavoritedAt(),
            row.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}
