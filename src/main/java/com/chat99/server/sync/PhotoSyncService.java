package com.chat99.server.sync;

import com.chat99.server.oss.ImageProcessor;
import com.chat99.server.oss.OssClient;
import com.chat99.server.oss.OssProperties;
import com.chat99.server.sync.SyncEnums.MediaType;
import com.chat99.server.sync.SyncEnums.PhotoCheckStatus;
import com.chat99.server.sync.SyncEnums.SyncMode;
import com.chat99.server.sync.SyncEnums.SyncType;
import com.chat99.server.sync.SyncEnums.UploadStatus;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PhotoSyncService {

    private final SyncSessionService sessionService;
    private final UserPhotoRepository photoRepository;
    private final UserPhotoUploadRepository uploadRepository;
    private final OssClient oss;
    private final OssProperties ossProps;
    private final ImageProcessor imageProcessor;
    private final SyncProperties syncProps;
    private final VideoSyncService videoSyncService;

    public PhotoSyncService(SyncSessionService sessionService,
                            UserPhotoRepository photoRepository,
                            UserPhotoUploadRepository uploadRepository,
                            OssClient oss,
                            OssProperties ossProps,
                            ImageProcessor imageProcessor,
                            SyncProperties syncProps,
                            VideoSyncService videoSyncService) {
        this.sessionService = sessionService;
        this.photoRepository = photoRepository;
        this.uploadRepository = uploadRepository;
        this.oss = oss;
        this.ossProps = ossProps;
        this.imageProcessor = imageProcessor;
        this.syncProps = syncProps;
        this.videoSyncService = videoSyncService;
    }

    public record SessionResponse(String syncSessionId, String syncType, String syncMode, String status) {}

    public record CheckItem(String localAssetId, String contentHash, Long sizeBytes, Long takenAt,
                            Integer width, Integer height, Integer duration,
                            String mediaType, String mimeType) {}

    public record CheckRequest(String syncSessionId, List<CheckItem> items) {}

    public record CheckResultItem(String localAssetId, String status, String photoUuid,
                                  String originUrl, String thumbUrl, String previewUrl) {}

    public record CheckResponse(List<CheckResultItem> results) {}

    public record InitUploadRequest(
        String syncSessionId,
        String localAssetId,
        String contentHash,
        Long sizeBytes,
        Long takenAt,
        Integer width,
        Integer height,
        Integer duration,
        String mediaType,
        String mimeType) {}

    public record InitUploadResponse(
        String uploadUuid,
        String photoUuid,
        String presignedPutUrl,
        String ossOriginKey,
        int presignExpiresInSeconds) {}

    public record CompleteRequest(String uploadUuid) {}

    public record CompleteResponse(
        String photoUuid,
        String localAssetId,
        String contentHash,
        String originUrl,
        String thumbUrl,
        String previewUrl,
        Long takenAt,
        Integer width,
        Integer height,
        Integer duration,
        String mediaType,
        String mimeType,
        Long sizeBytes) {}

    public record PhotoView(
        String photoUuid,
        String localAssetId,
        String contentHash,
        String originUrl,
        String thumbUrl,
        String previewUrl,
        Long takenAt,
        Integer width,
        Integer height,
        Long sizeBytes) {}

    public record PhotoListResponse(List<PhotoView> items, boolean hasMore) {}

    @Transactional
    public SessionResponse startSession(String userId, String deviceId, SyncMode mode) {
        SyncSession session = sessionService.createSession(userId, deviceId, SyncType.PHOTOS, mode);
        return new SessionResponse(session.getSessionUuid(), "PHOTOS", mode.name(), "RUNNING");
    }

    public CheckResponse check(String userId, CheckRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.items().size() > syncProps.maxPhotosCheck()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }
        if (req.syncSessionId() != null && !req.syncSessionId().isBlank()) {
            sessionService.requireRunningSession(userId, req.syncSessionId(), SyncType.PHOTOS);
        }

        List<CheckResultItem> results = new ArrayList<>();
        for (CheckItem item : req.items()) {
            validateHash(item.contentHash());
            boolean video = MediaSyncTypes.isVideo(item.mediaType(), item.mimeType());
            long maxBytes = video ? syncProps.maxVideoUploadBytes() : ossProps.maxUploadBytes();
            if (item.sizeBytes() != null && item.sizeBytes() > maxBytes) {
                results.add(new CheckResultItem(item.localAssetId(),
                    PhotoCheckStatus.SKIP_TOO_LARGE.name(), null, null, null, null));
                continue;
            }
            Optional<UserPhoto> existing = photoRepository.findByUserIdAndContentHashAndStatus(
                userId, item.contentHash(), 1);
            if (existing.isPresent()) {
                UserPhoto p = existing.get();
                if (!video || MediaSyncTypes.isVideo(p)) {
                    results.add(new CheckResultItem(item.localAssetId(),
                        PhotoCheckStatus.ALREADY_EXISTS.name(), p.getPhotoUuid(),
                        p.getOriginUrl(), p.getThumbUrl(), p.getPreviewUrl()));
                    continue;
                }
            }
            if (item.localAssetId() != null && !item.localAssetId().isBlank()) {
                Optional<UserPhoto> byLocal = photoRepository.findByUserIdAndLocalAssetIdAndStatus(
                    userId, item.localAssetId(), 1);
                if (byLocal.isPresent()) {
                    UserPhoto p = byLocal.get();
                    if (!video || MediaSyncTypes.isVideo(p)) {
                        results.add(new CheckResultItem(item.localAssetId(),
                            PhotoCheckStatus.ALREADY_EXISTS.name(), p.getPhotoUuid(),
                            p.getOriginUrl(), p.getThumbUrl(), p.getPreviewUrl()));
                        continue;
                    }
                }
            }
            results.add(new CheckResultItem(item.localAssetId(),
                PhotoCheckStatus.NEED_UPLOAD.name(), null, null, null, null));
        }
        return new CheckResponse(results);
    }

    @Transactional
    public InitUploadResponse initUpload(String userId, InitUploadRequest req) {
        if (MediaSyncTypes.isVideo(req.mediaType(), req.mimeType())) {
            VideoSyncService.InitUploadResponse video = videoSyncService.initUpload(userId,
                new VideoSyncService.InitUploadRequest(
                    req.syncSessionId(), req.localAssetId(), req.contentHash(), req.sizeBytes(),
                    req.takenAt(), req.width(), req.height(), req.duration(),
                    req.mediaType(), req.mimeType()));
            return new InitUploadResponse(
                video.uploadUuid(), video.photoUuid(), video.presignedPutUrl(),
                video.ossOriginKey(), video.presignExpiresInSeconds());
        }

        validateHash(req.contentHash());
        if (req.localAssetId() == null || req.localAssetId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.sizeBytes() != null && req.sizeBytes() > ossProps.maxUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }

        Optional<UserPhoto> existing = photoRepository.findByUserIdAndContentHashAndStatus(
            userId, req.contentHash(), 1);
        if (existing.isPresent()) {
            UserPhoto p = existing.get();
            return new InitUploadResponse(null, p.getPhotoUuid(), null, p.getOssOriginKey(), 0);
        }

        String photoUuid = UUID.randomUUID().toString();
        String uploadUuid = UUID.randomUUID().toString();
        String originKey = buildOriginKey(userId, req.contentHash());

        UserPhotoUpload pending = new UserPhotoUpload();
        pending.setUploadUuid(uploadUuid);
        pending.setPhotoUuid(photoUuid);
        pending.setUserId(userId);
        pending.setLocalAssetId(req.localAssetId());
        pending.setContentHash(req.contentHash());
        pending.setOssOriginKey(originKey);
        pending.setExpectedSize(req.sizeBytes());
        if (req.takenAt() != null) {
            pending.setTakenAt(Instant.ofEpochSecond(req.takenAt()));
        }
        pending.setWidth(req.width());
        pending.setHeight(req.height());
        pending.setMimeType(req.mimeType());
        pending.setMediaType(MediaType.IMAGE.name());
        pending.setSyncSessionId(req.syncSessionId());
        pending.setStatus(UploadStatus.PENDING);
        pending.setExpiresAt(Instant.now().plus(syncProps.uploadExpireMinutes(), ChronoUnit.MINUTES));
        uploadRepository.save(pending);

        String presigned = oss.presignedPutUrl(originKey, "image/jpeg", syncProps.presignExpireSeconds());
        return new InitUploadResponse(uploadUuid, photoUuid, presigned, originKey,
            syncProps.presignExpireSeconds());
    }

    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public CompleteResponse complete(String userId, CompleteRequest body, MultipartFile file) throws IOException {
        if (body.uploadUuid() == null || body.uploadUuid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }

        UserPhotoUpload upload = uploadRepository.findByUploadUuidAndUserIdAndStatus(
                body.uploadUuid(), userId, UploadStatus.PENDING)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND"));

        if (MediaType.VIDEO.name().equalsIgnoreCase(upload.getMediaType())) {
            VideoSyncService.CompleteResponse video = videoSyncService.complete(
                userId, new VideoSyncService.CompleteRequest(body.uploadUuid()), file);
            return toCompleteResponse(video);
        }

        if (upload.getExpiresAt().isBefore(Instant.now())) {
            upload.setStatus(UploadStatus.EXPIRED);
            uploadRepository.save(upload);
            throw new ResponseStatusException(HttpStatus.GONE, "UPLOAD_EXPIRED");
        }

        Optional<UserPhoto> byHash = photoRepository.findByUserIdAndContentHashAndStatus(
            userId, upload.getContentHash(), 1);
        if (byHash.isPresent()) {
            finishUploadRecord(upload);
            UserPhoto p = byHash.get();
            return toCompleteResponse(p);
        }

        byte[] originBytes;
        if (file != null && !file.isEmpty()) {
            if (file.getSize() > ossProps.maxUploadBytes()) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
            }
            originBytes = file.getBytes();
            oss.putBytes(upload.getOssOriginKey(), originBytes, file.getContentType());
        } else {
            if (!oss.exists(upload.getOssOriginKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OSS_OBJECT_NOT_FOUND");
            }
            originBytes = oss.getBytes(upload.getOssOriginKey());
        }

        BufferedImage img = imageProcessor.decode(originBytes);
        int q = ossProps.jpegQuality();
        byte[] previewJpeg = imageProcessor.resizeKeepAspect(img, ossProps.previewLongEdge(), q);
        byte[] thumbJpeg = imageProcessor.cropCenterSquare(img, ossProps.thumbSize(), q);

        String baseKey = upload.getOssOriginKey().replace("_origin.jpg", "");
        String previewKey = baseKey + "_preview.jpg";
        String thumbKey = baseKey + "_thumb.jpg";

        String originUrl = oss.putBytes(upload.getOssOriginKey(), originBytes, "image/jpeg");
        String previewUrl = oss.putBytes(previewKey, previewJpeg, "image/jpeg");
        String thumbUrl = oss.putBytes(thumbKey, thumbJpeg, "image/jpeg");

        photoRepository.findByUserIdAndLocalAssetIdAndStatus(userId, upload.getLocalAssetId(), 1)
            .filter(old -> !old.getContentHash().equals(upload.getContentHash()))
            .ifPresent(old -> {
                old.setStatus(0);
                photoRepository.save(old);
            });

        Optional<UserPhoto> sameLocal = photoRepository.findByUserIdAndLocalAssetIdAndStatus(
            userId, upload.getLocalAssetId(), 1);
        if (sameLocal.isPresent() && sameLocal.get().getContentHash().equals(upload.getContentHash())) {
            finishUploadRecord(upload);
            return toCompleteResponse(sameLocal.get());
        }

        UserPhoto photo = new UserPhoto();
        photo.setPhotoUuid(upload.getPhotoUuid());
        photo.setUserId(userId);
        photo.setLocalAssetId(upload.getLocalAssetId());
        photo.setContentHash(upload.getContentHash());
        photo.setSizeBytes(originBytes.length);
        photo.setMimeType("image/jpeg");
        photo.setMediaType(MediaType.IMAGE.name());
        photo.setOssOriginKey(upload.getOssOriginKey());
        photo.setOssPreviewKey(previewKey);
        photo.setOssThumbKey(thumbKey);
        photo.setOriginUrl(originUrl);
        photo.setPreviewUrl(previewUrl);
        photo.setThumbUrl(thumbUrl);
        photo.setSyncSessionId(upload.getSyncSessionId());
        photo.setStatus(1);
        photo.setWidth(upload.getWidth() != null ? upload.getWidth() : img.getWidth());
        photo.setHeight(upload.getHeight() != null ? upload.getHeight() : img.getHeight());
        if (upload.getTakenAt() != null) {
            photo.setTakenAt(upload.getTakenAt());
        }
        try {
            photoRepository.save(photo);
        } catch (DataIntegrityViolationException dup) {
            finishUploadRecord(upload);
            return photoRepository.findByUserIdAndLocalAssetIdAndStatus(userId, upload.getLocalAssetId(), 1)
                .map(this::toCompleteResponse)
                .or(() -> photoRepository.findByUserIdAndContentHashAndStatus(userId, upload.getContentHash(), 1)
                    .map(this::toCompleteResponse))
                .orElseThrow(() -> dup);
        }

        finishUploadRecord(upload);

        if (upload.getSyncSessionId() != null && !upload.getSyncSessionId().isBlank()) {
            sessionService.bumpUploadStatsIfRunning(userId, upload.getSyncSessionId());
        }

        return toCompleteResponse(photo);
    }

    public PhotoListResponse listPhotos(String userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        var pageable = PageRequest.of(safePage, safeSize);
        List<UserPhoto> rows = photoRepository.findImagesByUserId(userId, 1, pageable);
        boolean hasMore = rows.size() == safeSize;
        return new PhotoListResponse(rows.stream().map(this::toView).toList(), hasMore);
    }

    public PhotoView getPhoto(String userId, String photoUuid) {
        UserPhoto p = photoRepository.findByPhotoUuidAndUserId(photoUuid, userId)
            .filter(ph -> ph.getStatus() == 1 && isImage(ph))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND"));
        return toView(p);
    }

    @Transactional
    public ContactSyncService.CompleteResponse completePhotoSession(String userId, String syncSessionId) {
        SyncSession session = sessionService.requireRunningSession(userId, syncSessionId, SyncType.PHOTOS);
        sessionService.completeSession(session, session.getSyncMode());
        return new ContactSyncService.CompleteResponse(session.getSessionUuid(), "COMPLETED", 0);
    }

    private void sessionRepositoryOptionalBump(String userId, String sessionUuid) {
        sessionService.bumpUploadStatsIfRunning(userId, sessionUuid);
    }

    private void finishUploadRecord(UserPhotoUpload upload) {
        upload.setStatus(UploadStatus.COMPLETED);
        uploadRepository.save(upload);
    }

    private String buildOriginKey(String userId, String contentHash) {
        String prefix = contentHash.length() >= 2 ? contentHash.substring(0, 2) : "00";
        return syncProps.photoPrefix() + userId + "/photos/" + prefix + "/" + contentHash + "_origin.jpg";
    }

    private void validateHash(String hash) {
        if (hash == null || hash.isBlank() || hash.length() != 64 || !hash.matches("^[a-f0-9]{64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HASH");
        }
    }

    private CompleteResponse toCompleteResponse(UserPhoto p) {
        Long taken = p.getTakenAt() == null ? null : p.getTakenAt().getEpochSecond();
        return new CompleteResponse(
            p.getPhotoUuid(), p.getLocalAssetId(), p.getContentHash(),
            p.getOriginUrl(), p.getThumbUrl(), p.getPreviewUrl(), taken,
            p.getWidth(), p.getHeight(), p.getDurationSeconds(),
            p.getMediaType(), p.getMimeType(), p.getSizeBytes());
    }

    private CompleteResponse toCompleteResponse(VideoSyncService.CompleteResponse video) {
        return new CompleteResponse(
            video.photoUuid(), video.localAssetId(), video.contentHash(),
            video.originUrl(), video.thumbUrl(), video.previewUrl(), video.takenAt(),
            video.width(), video.height(), video.duration(),
            video.mediaType(), video.mimeType(), video.sizeBytes());
    }

    private PhotoView toView(UserPhoto p) {
        Long taken = p.getTakenAt() == null ? null : p.getTakenAt().getEpochSecond();
        return new PhotoView(p.getPhotoUuid(), p.getLocalAssetId(), p.getContentHash(),
            p.getOriginUrl(), p.getThumbUrl(), p.getPreviewUrl(), taken,
            p.getWidth(), p.getHeight(), p.getSizeBytes());
    }

    private static boolean isImage(UserPhoto photo) {
        return !MediaSyncTypes.isVideo(photo);
    }
}
