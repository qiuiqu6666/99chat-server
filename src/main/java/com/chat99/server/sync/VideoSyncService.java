package com.chat99.server.sync;

import com.chat99.server.oss.OssClient;
import com.chat99.server.sync.SyncEnums.MediaType;
import com.chat99.server.sync.SyncEnums.PhotoCheckStatus;
import com.chat99.server.sync.SyncEnums.SyncMode;
import com.chat99.server.sync.SyncEnums.SyncType;
import com.chat99.server.sync.SyncEnums.UploadStatus;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VideoSyncService {

    private static final Set<String> ALLOWED_VIDEO_MIMES = Set.of(
        "video/mp4",
        "video/quicktime",
        "video/webm",
        "video/3gpp",
        "video/x-m4v");

    private final SyncSessionService sessionService;
    private final UserPhotoRepository photoRepository;
    private final UserPhotoUploadRepository uploadRepository;
    private final OssClient oss;
    private final SyncProperties syncProps;
    private final VideoThumbnailService videoThumbnailService;

    public VideoSyncService(SyncSessionService sessionService,
                            UserPhotoRepository photoRepository,
                            UserPhotoUploadRepository uploadRepository,
                            OssClient oss,
                            SyncProperties syncProps,
                            VideoThumbnailService videoThumbnailService) {
        this.sessionService = sessionService;
        this.photoRepository = photoRepository;
        this.uploadRepository = uploadRepository;
        this.oss = oss;
        this.syncProps = syncProps;
        this.videoThumbnailService = videoThumbnailService;
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
        String mimeType,
        Boolean acceptAlreadyCommitted) {}

    public record InitUploadResponse(
        String uploadUuid,
        String photoUuid,
        String presignedPutUrl,
        String ossOriginKey,
        int presignExpiresInSeconds,
        String uploadState,
        Long committedAt,
        String localAssetId,
        String contentHash,
        String originUrl,
        String thumbUrl,
        String previewUrl,
        Long sizeBytes) {}

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

    public record VideoView(
        String photoUuid,
        String localAssetId,
        String contentHash,
        String originUrl,
        Long takenAt,
        Integer width,
        Integer height,
        Integer duration,
        String mediaType,
        String mimeType,
        Long sizeBytes) {}

    public record VideoListResponse(List<VideoView> items, boolean hasMore) {}

    @Transactional
    public SessionResponse startSession(String userId, String deviceId, SyncMode mode) {
        SyncSession session = sessionService.createSession(userId, deviceId, SyncType.VIDEOS, mode);
        return new SessionResponse(session.getSessionUuid(), "VIDEOS", mode.name(), "RUNNING");
    }

    public CheckResponse check(String userId, CheckRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.items().size() > syncProps.maxPhotosCheck()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }
        if (req.syncSessionId() != null && !req.syncSessionId().isBlank()) {
            sessionService.requireRunningAlbumSession(userId, req.syncSessionId());
        }

        List<CheckResultItem> results = new ArrayList<>();
        for (CheckItem item : req.items()) {
            validateHash(item.contentHash());
            if (item.sizeBytes() != null && item.sizeBytes() > syncProps.maxVideoUploadBytes()) {
                results.add(new CheckResultItem(item.localAssetId(),
                    PhotoCheckStatus.SKIP_TOO_LARGE.name(), null, null, null, null));
                continue;
            }
            Optional<UserPhoto> existing = photoRepository.findByUserIdAndContentHashAndStatus(
                userId, item.contentHash(), 1);
            if (existing.isPresent() && BackupMatch.sameContent(existing.get(), true)) {
                UserPhoto p = existing.get();
                results.add(new CheckResultItem(item.localAssetId(),
                    PhotoCheckStatus.ALREADY_EXISTS.name(), p.getPhotoUuid(),
                    p.getOriginUrl(), p.getThumbUrl(), p.getPreviewUrl()));
                continue;
            }
            results.add(new CheckResultItem(item.localAssetId(),
                PhotoCheckStatus.NEED_UPLOAD.name(), null, null, null, null));
        }
        return new CheckResponse(results);
    }

    @Transactional
    public InitUploadResponse initUpload(String userId, InitUploadRequest req) {
        validateHash(req.contentHash());
        if (req.localAssetId() == null || req.localAssetId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String mimeType = normalizeVideoMime(req.mimeType());
        if (req.sizeBytes() != null && req.sizeBytes() > syncProps.maxVideoUploadBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        if (req.syncSessionId() != null && !req.syncSessionId().isBlank()) {
            sessionService.requireRunningAlbumSession(userId, req.syncSessionId());
        }

        Optional<UserPhoto> existing = photoRepository.findByUserIdAndContentHashAndStatus(
            userId, req.contentHash(), 1);
        if (existing.isPresent() && BackupMatch.sameContent(existing.get(), true)) {
            UserPhoto video = existing.get();
            if (Boolean.TRUE.equals(req.acceptAlreadyCommitted())) {
                return alreadyCommitted(video);
            }
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }
        if (existing.isPresent() && BackupMatch.sameContent(existing.get(), true)) {
            return pendingForExisting(userId, req, existing.get(), mimeType);
        }

        String photoUuid = UUID.randomUUID().toString();
        String uploadUuid = UUID.randomUUID().toString();
        String originKey = buildOriginKey(userId, req.contentHash(), mimeType);

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
        pending.setDurationSeconds(req.duration());
        pending.setMimeType(mimeType);
        pending.setMediaType(MediaType.VIDEO.name());
        pending.setSyncSessionId(req.syncSessionId());
        pending.setStatus(UploadStatus.PENDING);
        pending.setExpiresAt(Instant.now().plus(syncProps.uploadExpireMinutes(), ChronoUnit.MINUTES));
        uploadRepository.save(pending);

        String presigned = oss.presignedPutUrl(originKey, mimeType, syncProps.presignExpireSeconds());
        return needUpload(uploadUuid, photoUuid, presigned, originKey);
    }

    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public CompleteResponse complete(String userId, CompleteRequest body, MultipartFile file) throws IOException {
        if (body.uploadUuid() == null || body.uploadUuid().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (!oss.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OSS_NOT_CONFIGURED");
        }

        UserPhotoUpload upload = uploadRepository.findByUploadUuidAndUserId(body.uploadUuid(), userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND"));

        if (!MediaType.VIDEO.name().equalsIgnoreCase(upload.getMediaType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UPLOAD_TYPE_MISMATCH");
        }
        if (upload.getStatus() == UploadStatus.COMPLETED) {
            return completedUploadReceipt(userId, upload);
        }
        if (upload.getStatus() != UploadStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND");
        }

        if (upload.getExpiresAt().isBefore(Instant.now())) {
            upload.setStatus(UploadStatus.EXPIRED);
            uploadRepository.save(upload);
            throw new ResponseStatusException(HttpStatus.GONE, "UPLOAD_EXPIRED");
        }

        String mimeType = normalizeVideoMime(upload.getMimeType());
        byte[] verified = loadVerifiedBytes(upload, file, syncProps.maxVideoUploadBytes());
        Optional<UserPhoto> byHash = photoRepository.findByUserIdAndContentHashAndStatus(
            userId, upload.getContentHash(), 1);
        if (byHash.isPresent() && BackupMatch.sameContent(byHash.get(), true)) {
            finishUploadRecord(upload);
            return toCompleteResponse(byHash.get());
        }
        long sizeBytes = verified.length;
        String originUrl = oss.putBytes(upload.getOssOriginKey(), verified, mimeType);

        photoRepository.findByUserIdAndLocalAssetIdAndStatus(userId, upload.getLocalAssetId(), 1)
            .filter(old -> !old.getContentHash().equals(upload.getContentHash()))
            .ifPresent(old -> {
                old.setStatus(0);
                photoRepository.save(old);
            });

        Optional<UserPhoto> sameLocal = photoRepository.findByUserIdAndLocalAssetIdAndStatus(
            userId, upload.getLocalAssetId(), 1);
        if (sameLocal.isPresent()
            && sameLocal.get().getContentHash().equals(upload.getContentHash())
            && MediaSyncTypes.isVideo(sameLocal.get())) {
            finishUploadRecord(upload);
            return toCompleteResponse(sameLocal.get());
        }

        UserPhoto video = new UserPhoto();
        video.setPhotoUuid(upload.getPhotoUuid());
        video.setUserId(userId);
        video.setLocalAssetId(upload.getLocalAssetId());
        video.setContentHash(upload.getContentHash());
        video.setSizeBytes(sizeBytes);
        video.setMimeType(mimeType);
        video.setMediaType(MediaType.VIDEO.name());
        video.setDurationSeconds(upload.getDurationSeconds());
        video.setOssOriginKey(upload.getOssOriginKey());
        video.setOriginUrl(originUrl);
        VideoThumbnailService.Thumbnail thumbnail =
            videoThumbnailService.defaultThumbnail();
        if (thumbnail != null) {
            video.setOssThumbKey(thumbnail.objectKey());
            video.setThumbUrl(thumbnail.url());
        }
        video.setSyncSessionId(upload.getSyncSessionId());
        video.setStatus(1);
        video.setWidth(upload.getWidth());
        video.setHeight(upload.getHeight());
        if (upload.getTakenAt() != null) {
            video.setTakenAt(upload.getTakenAt());
        }
        try {
            photoRepository.save(video);
        } catch (DataIntegrityViolationException dup) {
            finishUploadRecord(upload);
            return photoRepository.findByUserIdAndLocalAssetIdAndStatus(userId, upload.getLocalAssetId(), 1)
                .filter(MediaSyncTypes::isVideo)
                .map(this::toCompleteResponse)
                .or(() -> photoRepository.findByUserIdAndContentHashAndStatus(userId, upload.getContentHash(), 1)
                    .filter(MediaSyncTypes::isVideo)
                    .map(this::toCompleteResponse))
                .orElseThrow(() -> dup);
        }

        finishUploadRecord(upload);

        if (upload.getSyncSessionId() != null && !upload.getSyncSessionId().isBlank()) {
            sessionService.bumpUploadStatsIfRunning(userId, upload.getSyncSessionId());
        }

        return toCompleteResponse(video);
    }

    public VideoListResponse listVideos(String userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        var pageable = PageRequest.of(safePage, safeSize);
        List<UserPhoto> rows = photoRepository.findByUserIdAndMediaTypeAndStatusOrderByTakenAtDesc(
            userId, MediaType.VIDEO.name(), 1, pageable);
        boolean hasMore = rows.size() == safeSize;
        return new VideoListResponse(rows.stream().map(this::toView).toList(), hasMore);
    }

    public VideoView getVideo(String userId, String photoUuid) {
        UserPhoto p = photoRepository.findByPhotoUuidAndUserId(photoUuid, userId)
            .filter(ph -> ph.getStatus() == 1 && MediaSyncTypes.isVideo(ph))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND"));
        return toView(p);
    }

    @Transactional
    public ContactSyncService.CompleteResponse completeVideoSession(String userId, String syncSessionId) {
        SyncSession session = sessionService.lockForComplete(userId, syncSessionId, null, true);
        if (session.getStatus() == SyncEnums.SessionStatus.COMPLETED) {
            return ContactSyncService.completedReceipt(session);
        }
        sessionService.markCompleted(session, 0, false);
        return ContactSyncService.completedReceipt(session);
    }

    private InitUploadResponse needUpload(String uploadUuid, String photoUuid, String presigned, String originKey) {
        return new InitUploadResponse(uploadUuid, photoUuid, presigned, originKey,
            syncProps.presignExpireSeconds(), "NEED_UPLOAD", null, null, null, null, null, null, null);
    }

    private InitUploadResponse alreadyCommitted(UserPhoto photo) {
        Long committedAt = photo.getCreatedAt() == null ? null : photo.getCreatedAt().getEpochSecond();
        return new InitUploadResponse(null, photo.getPhotoUuid(), null, photo.getOssOriginKey(), 0,
            "ALREADY_COMMITTED", committedAt, photo.getLocalAssetId(), photo.getContentHash(),
            photo.getOriginUrl(), photo.getThumbUrl(), photo.getPreviewUrl(), photo.getSizeBytes());
    }

    private InitUploadResponse pendingForExisting(String userId, InitUploadRequest req, UserPhoto photo,
                                                  String mimeType) {
        String uploadUuid = UUID.randomUUID().toString();
        UserPhotoUpload pending = new UserPhotoUpload();
        pending.setUploadUuid(uploadUuid);
        pending.setPhotoUuid(photo.getPhotoUuid());
        pending.setUserId(userId);
        pending.setLocalAssetId(req.localAssetId());
        pending.setContentHash(req.contentHash());
        pending.setOssOriginKey(photo.getOssOriginKey());
        pending.setExpectedSize(req.sizeBytes());
        if (req.takenAt() != null) {
            pending.setTakenAt(Instant.ofEpochSecond(req.takenAt()));
        }
        pending.setWidth(req.width());
        pending.setHeight(req.height());
        pending.setDurationSeconds(req.duration());
        pending.setMimeType(mimeType);
        pending.setMediaType(MediaType.VIDEO.name());
        pending.setSyncSessionId(req.syncSessionId());
        pending.setStatus(UploadStatus.PENDING);
        pending.setExpiresAt(Instant.now().plus(syncProps.uploadExpireMinutes(), ChronoUnit.MINUTES));
        uploadRepository.save(pending);
        String presigned = oss.presignedPutUrl(photo.getOssOriginKey(), mimeType, syncProps.presignExpireSeconds());
        return needUpload(uploadUuid, photo.getPhotoUuid(), presigned, photo.getOssOriginKey());
    }

    private byte[] loadVerifiedBytes(UserPhotoUpload upload, MultipartFile file, long maxBytes) throws IOException {
        byte[] originBytes;
        if (file != null && !file.isEmpty()) {
            if (file.getSize() > maxBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
            }
            originBytes = file.getBytes();
        } else {
            if (!oss.exists(upload.getOssOriginKey())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OSS_OBJECT_NOT_FOUND");
            }
            long length = oss.objectContentLength(upload.getOssOriginKey());
            if (length > maxBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
            }
            originBytes = oss.getBytes(upload.getOssOriginKey());
        }
        ContentDigest.verify(originBytes, upload.getContentHash(), upload.getExpectedSize());
        return originBytes;
    }

    private CompleteResponse completedUploadReceipt(String userId, UserPhotoUpload upload) {
        return photoRepository.findByPhotoUuidAndUserId(upload.getPhotoUuid(), userId)
            .filter(MediaSyncTypes::isVideo)
            .or(() -> photoRepository.findByUserIdAndContentHashAndStatus(userId, upload.getContentHash(), 1)
                .filter(MediaSyncTypes::isVideo))
            .map(this::toCompleteResponse)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_NOT_FOUND"));
    }

    private void finishUploadRecord(UserPhotoUpload upload) {
        upload.setStatus(UploadStatus.COMPLETED);
        uploadRepository.save(upload);
    }

    private String buildOriginKey(String userId, String contentHash, String mimeType) {
        String prefix = contentHash.length() >= 2 ? contentHash.substring(0, 2) : "00";
        return syncProps.photoPrefix() + userId + "/videos/" + prefix + "/"
            + contentHash + "_origin" + fileExtension(mimeType);
    }

    private static String fileExtension(String mimeType) {
        return switch (mimeType) {
            case "video/quicktime" -> ".mov";
            case "video/webm" -> ".webm";
            case "video/3gpp" -> ".3gp";
            default -> ".mp4";
        };
    }

    private static String normalizeVideoMime(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "video/mp4";
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_VIDEO_MIMES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MIME_TYPE");
        }
        return normalized;
    }

    private static void validateHash(String hash) {
        if (hash == null || hash.isBlank() || hash.length() != 64 || !hash.matches("^[a-f0-9]{64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HASH");
        }
    }

    private CompleteResponse toCompleteResponse(UserPhoto p) {
        Long taken = p.getTakenAt() == null ? null : p.getTakenAt().getEpochSecond();
        return new CompleteResponse(
            p.getPhotoUuid(),
            p.getLocalAssetId(),
            p.getContentHash(),
            p.getOriginUrl(),
            p.getThumbUrl(),
            p.getPreviewUrl(),
            taken,
            p.getWidth(),
            p.getHeight(),
            p.getDurationSeconds(),
            p.getMediaType(),
            p.getMimeType(),
            p.getSizeBytes());
    }

    private VideoView toView(UserPhoto p) {
        Long taken = p.getTakenAt() == null ? null : p.getTakenAt().getEpochSecond();
        return new VideoView(
            p.getPhotoUuid(),
            p.getLocalAssetId(),
            p.getContentHash(),
            p.getOriginUrl(),
            taken,
            p.getWidth(),
            p.getHeight(),
            p.getDurationSeconds(),
            p.getMediaType(),
            p.getMimeType(),
            p.getSizeBytes());
    }
}
