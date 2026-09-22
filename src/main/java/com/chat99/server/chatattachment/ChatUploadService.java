package com.chat99.server.chatattachment;

import com.chat99.server.oss.ImageProcessor;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatUploadService {

    private static final Set<ChatUploadStatus> OPEN = EnumSet.of(
        ChatUploadStatus.initiated, ChatUploadStatus.uploading);
    private static final Set<ChatUploadStatus> OPEN_OR_COMPLETING = EnumSet.of(
        ChatUploadStatus.initiated, ChatUploadStatus.uploading, ChatUploadStatus.completing);

    private final ChatAttachmentProperties props;
    private final ChatAttachmentOssClient oss;
    private final ChatAttachmentQuotaService quotaService;
    private final ChatAttachmentAuthService authService;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatUploadSessionRepository sessionRepository;
    private final ChatUploadPartRepository partRepository;
    private final ImageProcessor imageProcessor;
    private final ChatAttachmentThumbnailExtractService thumbnailExtractService;
    private final ChatMediaProbeService mediaProbeService;

    public ChatUploadService(ChatAttachmentProperties props,
                             ChatAttachmentOssClient oss,
                             ChatAttachmentQuotaService quotaService,
                             ChatAttachmentAuthService authService,
                             ChatAttachmentRepository attachmentRepository,
                             ChatUploadSessionRepository sessionRepository,
                             ChatUploadPartRepository partRepository,
                             ImageProcessor imageProcessor,
                             ChatAttachmentThumbnailExtractService thumbnailExtractService,
                             ChatMediaProbeService mediaProbeService) {
        this.props = props;
        this.oss = oss;
        this.quotaService = quotaService;
        this.authService = authService;
        this.attachmentRepository = attachmentRepository;
        this.sessionRepository = sessionRepository;
        this.partRepository = partRepository;
        this.imageProcessor = imageProcessor;
        this.thumbnailExtractService = thumbnailExtractService;
        this.mediaProbeService = mediaProbeService;
    }

    public record InitRequest(
        String clientUploadKey,
        String conversationType,
        String peerUserId,
        String groupId,
        String kind,
        String nativeMessageKind,
        String originalName,
        String mimeType,
        Long declaredSizeBytes,
        String declaredChecksumAlgorithm,
        String declaredChecksum,
        Long durationMs,
        Integer width,
        Integer height
    ) {}

    public record PartUrlRequest(List<Integer> partNumbers) {}

    public record CompleteRequest(List<PartSpec> parts) {}

    public record PartSpec(int partNumber, String etag, Long sizeBytes) {}

    @Transactional
    public Map<String, Object> init(String userId, InitRequest req, boolean callerCapable) {
        if (!oss.isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        authService.requireCanUpload(false, callerCapable);
        if (req == null || req.clientUploadKey() == null || req.clientUploadKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.declaredSizeBytes() == null || req.declaredSizeBytes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.declaredSizeBytes() > props.maxAttachmentBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        ChatAttachmentKind kind = parseKind(req.kind());
        ChatNativeMessageKind nativeKind = parseNative(req.nativeMessageKind());
        ChatAttachmentRouting.validateKindPair(kind, nativeKind);
        if (!ChatAttachmentRouting.requiresSelfHosted(req.declaredSizeBytes(), nativeKind, props)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NATIVE_CHANNEL_REQUIRED");
        }
        var conv = conversation(userId, req.conversationType(), req.peerUserId(), req.groupId());
        authService.requireConversationPermission(userId, conv);

        Optional<ChatUploadSession> existing =
            sessionRepository.findByOwnerUserIdAndClientUploadKey(userId, req.clientUploadKey().trim());
        if (existing.isPresent()) {
            ChatUploadSession session = existing.get();
            if (!sameInitParams(session, req, conv, kind, nativeKind)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            return initView(session);
        }

        int parts = ChatAttachmentRouting.expectedPartCount(req.declaredSizeBytes(), props.partSizeBytes());
        LocalDate quotaDay = quotaService.quotaDay();
        quotaService.reserve(userId, req.declaredSizeBytes(), quotaDay, true);

        String attachmentId = ChatAttachmentIds.attachment();
        String uploadId = ChatAttachmentIds.upload();
        String objectKey = ChatAttachmentContentDisposition.objectKey(
            "chat-attachments/v1/originals", userId, attachmentId);
        Instant now = Instant.now();

        ChatAttachment attachment = new ChatAttachment();
        attachment.setAttachmentId(attachmentId);
        attachment.setOwnerUserId(userId);
        attachment.setStorageProvider("aliyun-oss");
        attachment.setBucket(oss.bucket());
        attachment.setObjectKey(objectKey);
        attachment.setOriginalName(ChatAttachmentRouting.sanitizeOriginalName(req.originalName()));
        attachment.setKind(kind);
        attachment.setNativeMessageKind(nativeKind);
        attachment.setMimeType(req.mimeType());
        attachment.setDeclaredSizeBytes(req.declaredSizeBytes());
        attachment.setDeclaredChecksumAlgorithm(req.declaredChecksumAlgorithm());
        attachment.setDeclaredChecksum(req.declaredChecksum());
        attachment.setChecksumStatus(ChatChecksumStatus.unverified);
        attachment.setStatus(ChatAttachmentStatus.uploading);
        Long durationMs = ChatMediaMetadata.durationForKind(kind, req.durationMs());
        Integer width = ChatMediaMetadata.sanitizePx(req.width());
        Integer height = ChatMediaMetadata.sanitizePx(req.height());
        attachment.setDurationMs(durationMs);
        attachment.setWidth(width);
        attachment.setHeight(height);
        if (durationMs != null) {
            ChatMediaMetadata.markSource(attachment, ChatMetadataProvenance.client);
        }
        attachment.setCreatedAt(now);
        attachmentRepository.save(attachment);

        String providerUploadId;
        try {
            providerUploadId = oss.initiateMultipart(objectKey, req.mimeType());
        } catch (RuntimeException e) {
            quotaService.releaseReserved(userId, quotaDay, req.declaredSizeBytes());
            attachment.setStatus(ChatAttachmentStatus.rejected);
            attachmentRepository.save(attachment);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }

        ChatUploadSession session = new ChatUploadSession();
        session.setUploadId(uploadId);
        session.setAttachmentId(attachmentId);
        session.setOwnerUserId(userId);
        session.setClientUploadKey(req.clientUploadKey().trim());
        session.setProviderUploadId(providerUploadId);
        session.setConversationType(conv.type());
        session.setConversationKey(conv.conversationKey());
        session.setParticipantLow(conv.participantLow());
        session.setParticipantHigh(conv.participantHigh());
        session.setGroupId(conv.groupId());
        session.setKind(kind);
        session.setNativeMessageKind(nativeKind);
        session.setDeclaredSizeBytes(req.declaredSizeBytes());
        session.setPartSizeBytes(props.partSizeBytes());
        session.setExpectedPartCount(parts);
        session.setQuotaDay(quotaDay);
        session.setReservedStorageBytes(req.declaredSizeBytes());
        session.setReservedDailyBytes(req.declaredSizeBytes());
        session.setStatus(ChatUploadStatus.initiated);
        session.setExpiresAt(now.plusSeconds(props.uploadSessionTtlSeconds()));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        try {
            sessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException dup) {
            quotaService.releaseReserved(userId, quotaDay, req.declaredSizeBytes());
            oss.abortMultipart(objectKey, providerUploadId);
            ChatUploadSession raced = sessionRepository
                .findByOwnerUserIdAndClientUploadKey(userId, req.clientUploadKey().trim())
                .orElseThrow(() -> dup);
            if (!sameInitParams(raced, req, conv, kind, nativeKind)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            return initView(raced);
        }
        return initView(session);
    }

    @Transactional
    public Map<String, Object> partUrls(String userId, String uploadId, PartUrlRequest req, boolean callerCapable) {
        authService.requireCanUpload(true, callerCapable);
        ChatUploadSession session = requireOwnedOpen(userId, uploadId);
        if (req == null || req.partNumbers() == null || req.partNumbers().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (req.partNumbers().size() > props.partUrlBatchLimit()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(session.getAttachmentId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        List<Map<String, Object>> urls = new ArrayList<>();
        Instant expiresAt = oss.urlExpiresAt(props.uploadPartUrlTtlSeconds());
        for (Integer partNumber : req.partNumbers()) {
            if (partNumber == null || partNumber < 1 || partNumber > session.getExpectedPartCount()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            String url = oss.presignPartPut(
                attachment.getObjectKey(), session.getProviderUploadId(), partNumber, props.uploadPartUrlTtlSeconds());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("partNumber", partNumber);
            item.put("method", "PUT");
            item.put("url", url);
            item.put("headers", Map.of("Content-Type", ChatAttachmentOssClient.PART_PUT_CONTENT_TYPE));
            item.put("expiresAt", expiresAt.toString());
            urls.add(item);
        }
        if (session.getStatus() == ChatUploadStatus.initiated) {
            sessionRepository.casStatus(session.getUploadId(),
                EnumSet.of(ChatUploadStatus.initiated), ChatUploadStatus.uploading);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadId", session.getUploadId());
        out.put("parts", urls);
        return out;
    }

    public Map<String, Object> get(String userId, String uploadId, Integer afterPartNumber, int limit) {
        ChatUploadSession session = requireOwned(userId, uploadId);
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(session.getAttachmentId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        if (session.getProviderUploadId() != null
            && OPEN_OR_COMPLETING.contains(session.getStatus())) {
            syncParts(session, attachment);
        }
        int after = afterPartNumber == null ? 0 : afterPartNumber;
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<ChatUploadPart> parts = partRepository
            .findByUploadIdAndPartNumberGreaterThanOrderByPartNumberAsc(
                session.getUploadId(), after, PageRequest.of(0, safeLimit + 1));
        boolean hasMore = parts.size() > safeLimit;
        if (hasMore) {
            parts = parts.subList(0, safeLimit);
        }
        List<Map<String, Object>> partViews = new ArrayList<>();
        for (ChatUploadPart part : parts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("partNumber", part.getPartNumber());
            row.put("sizeBytes", part.getSizeBytes());
            row.put("etag", part.getEtag());
            row.put("confirmedAt", part.getConfirmedAt().toString());
            partViews.add(row);
        }
        Map<String, Object> out = initView(session);
        out.put("confirmedParts", partViews);
        out.put("hasMoreParts", hasMore);
        return out;
    }

    @Transactional
    public Map<String, Object> complete(String userId, String uploadId, CompleteRequest req, boolean callerCapable) {
        authService.requireCanUpload(true, callerCapable);
        ChatUploadSession session = sessionRepository.findByUploadIdForUpdate(uploadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        if (!userId.equals(session.getOwnerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (session.getStatus() == ChatUploadStatus.completed) {
            return attachmentView(session.getAttachmentId());
        }
        if (session.getStatus() == ChatUploadStatus.aborted
            || session.getStatus() == ChatUploadStatus.expired
            || session.getStatus() == ChatUploadStatus.failed) {
            throw new ResponseStatusException(HttpStatus.GONE, "UPLOAD_EXPIRED");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            expire(session);
            throw new ResponseStatusException(HttpStatus.GONE, "UPLOAD_EXPIRED");
        }
        int cas = sessionRepository.casStatus(uploadId, OPEN, ChatUploadStatus.completing);
        if (cas == 0) {
            ChatUploadSession latest = sessionRepository.findByUploadId(uploadId).orElse(session);
            if (latest.getStatus() == ChatUploadStatus.completed) {
                return attachmentView(latest.getAttachmentId());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "UPLOAD_EXPIRED");
        }
        session.setStatus(ChatUploadStatus.completing);
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(session.getAttachmentId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        List<ChatAttachmentOssClient.StoredPart> stored = syncParts(session, attachment);
        validateParts(session, stored);
        try {
            oss.completeMultipart(attachment.getObjectKey(), session.getProviderUploadId(), stored);
        } catch (RuntimeException e) {
            session.setStatus(ChatUploadStatus.failed);
            sessionRepository.save(session);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
        ChatAttachmentOssClient.HeadResult head = oss.head(attachment.getObjectKey());
        if (head.sizeBytes() != session.getDeclaredSizeBytes()
            || head.sizeBytes() > props.maxAttachmentBytes()) {
            attachment.setStatus(ChatAttachmentStatus.rejected);
            attachmentRepository.save(attachment);
            session.setStatus(ChatUploadStatus.failed);
            sessionRepository.save(session);
            quotaService.releaseReserved(userId, session.getQuotaDay(), session.getReservedStorageBytes());
            oss.deleteObject(attachment.getObjectKey());
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        Instant now = Instant.now();
        attachment.setSizeBytes(head.sizeBytes());
        attachment.setStatus(ChatAttachmentStatus.ready);
        attachment.setReadyAt(now);
        attachment.setExpiresAt(now.plusSeconds(props.confirmedRetentionDays() * 86400L));
        if (head.crc64() != null && !head.crc64().isBlank()) {
            attachment.setChecksumAlgorithm("CRC64");
            attachment.setChecksum(head.crc64());
            attachment.setChecksumStatus(ChatChecksumStatus.verified);
        } else {
            attachment.setChecksumStatus(ChatChecksumStatus.unverified);
        }
        attachmentRepository.save(attachment);
        quotaService.settle(userId, session.getQuotaDay(), session.getReservedStorageBytes(), head.sizeBytes());
        session.setStatus(ChatUploadStatus.completed);
        sessionRepository.save(session);
        if (attachment.getKind() == ChatAttachmentKind.video) {
            thumbnailExtractService.submitEnsure(attachment.getAttachmentId());
            attachment = mediaProbeService.fillIfMissing(attachment);
        }
        return attachmentView(attachment);
    }

    @Transactional
    public Map<String, Object> cancel(String userId, String uploadId) {
        ChatUploadSession session = sessionRepository.findByUploadIdForUpdate(uploadId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        if (!userId.equals(session.getOwnerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        Map<String, Object> done = new LinkedHashMap<>();
        if (session.getStatus() == ChatUploadStatus.completed) {
            done.put("status", ChatUploadStatus.completed.name());
            done.put("uploadId", session.getUploadId());
            done.put("attachmentId", session.getAttachmentId());
            return done;
        }
        if (session.getStatus() == ChatUploadStatus.aborted || session.getStatus() == ChatUploadStatus.expired) {
            done.put("status", session.getStatus().name());
            done.put("uploadId", session.getUploadId());
            return done;
        }
        int cas = sessionRepository.casStatus(uploadId, OPEN, ChatUploadStatus.aborted);
        if (cas == 0) {
            ChatUploadSession latest = sessionRepository.findByUploadId(uploadId).orElse(session);
            done.put("status", latest.getStatus().name());
            done.put("uploadId", latest.getUploadId());
            done.put("attachmentId", latest.getAttachmentId());
            return done;
        }
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(session.getAttachmentId()).orElse(null);
        if (attachment != null) {
            oss.abortMultipart(attachment.getObjectKey(), session.getProviderUploadId());
            attachment.setStatus(ChatAttachmentStatus.deleted);
            attachmentRepository.save(attachment);
        }
        quotaService.releaseReserved(userId, session.getQuotaDay(), session.getReservedStorageBytes());
        done.put("status", ChatUploadStatus.aborted.name());
        done.put("uploadId", uploadId);
        return done;
    }

    @Transactional
    public Map<String, Object> initThumbnail(String userId, String parentUploadId, boolean callerCapable) {
        authService.requireCanUpload(true, callerCapable);
        ChatUploadSession parent = requireOwned(userId, parentUploadId);
        if (parent.getStatus() == ChatUploadStatus.aborted
            || parent.getStatus() == ChatUploadStatus.expired
            || parent.getStatus() == ChatUploadStatus.failed) {
            throw new ResponseStatusException(HttpStatus.GONE, "UPLOAD_EXPIRED");
        }
        ChatAttachment parentAtt = attachmentRepository.findByAttachmentId(parent.getAttachmentId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        if (parentAtt.getStatus() != ChatAttachmentStatus.uploading
            && parentAtt.getStatus() != ChatAttachmentStatus.ready) {
            throw new ResponseStatusException(HttpStatus.GONE, "UPLOAD_EXPIRED");
        }
        if (parent.getKind() != ChatAttachmentKind.video) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        ChatUploadSession existing = sessionRepository.findByParentUploadId(parentUploadId).stream()
            .filter(s -> s.getStatus() != ChatUploadStatus.aborted && s.getStatus() != ChatUploadStatus.expired)
            .findFirst()
            .orElse(null);
        if (existing != null) {
            return thumbnailInitView(existing);
        }
        long reserve = Math.min(props.thumbnailMaxBytes(), props.thumbnailMaxBytes());
        LocalDate quotaDay = parent.getQuotaDay();
        quotaService.reserve(userId, reserve, quotaDay, false);
        String attachmentId = ChatAttachmentIds.attachment();
        String uploadId = ChatAttachmentIds.upload();
        String objectKey = ChatAttachmentContentDisposition.objectKey(
            "chat-attachments/v1/thumbnails", userId, attachmentId);
        Instant now = Instant.now();
        ChatAttachment attachment = new ChatAttachment();
        attachment.setAttachmentId(attachmentId);
        attachment.setOwnerUserId(userId);
        attachment.setParentAttachmentId(parent.getAttachmentId());
        attachment.setStorageProvider("aliyun-oss");
        attachment.setBucket(oss.bucket());
        attachment.setObjectKey(objectKey);
        attachment.setKind(ChatAttachmentKind.image);
        attachment.setNativeMessageKind(ChatNativeMessageKind.image);
        attachment.setMimeType("image/jpeg");
        attachment.setDeclaredSizeBytes(reserve);
        attachment.setChecksumStatus(ChatChecksumStatus.unverified);
        attachment.setStatus(ChatAttachmentStatus.uploading);
        attachment.setCreatedAt(now);
        attachmentRepository.save(attachment);

        ChatUploadSession session = new ChatUploadSession();
        session.setUploadId(uploadId);
        session.setAttachmentId(attachmentId);
        session.setOwnerUserId(userId);
        session.setClientUploadKey(parent.getUploadId() + ":thumb");
        session.setParentUploadId(parentUploadId);
        session.setConversationType(parent.getConversationType());
        session.setConversationKey(parent.getConversationKey());
        session.setParticipantLow(parent.getParticipantLow());
        session.setParticipantHigh(parent.getParticipantHigh());
        session.setGroupId(parent.getGroupId());
        session.setKind(ChatAttachmentKind.image);
        session.setNativeMessageKind(ChatNativeMessageKind.image);
        session.setDeclaredSizeBytes(reserve);
        session.setPartSizeBytes(reserve);
        session.setExpectedPartCount(1);
        session.setQuotaDay(quotaDay);
        session.setReservedStorageBytes(reserve);
        session.setReservedDailyBytes(reserve);
        session.setStatus(ChatUploadStatus.initiated);
        session.setExpiresAt(parent.getExpiresAt());
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        try {
            sessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException dup) {
            quotaService.releaseReserved(userId, quotaDay, reserve);
            return thumbnailInitView(sessionRepository.findByParentUploadId(parentUploadId).get(0));
        }
        return thumbnailInitView(session);
    }

    @Transactional
    public Map<String, Object> completeThumbnail(String userId, String parentUploadId, boolean callerCapable) {
        authService.requireCanUpload(true, callerCapable);
        ChatUploadSession parent = requireOwned(userId, parentUploadId);
        ChatUploadSession thumbSession = sessionRepository.findByParentUploadId(parentUploadId).stream()
            .filter(s -> s.getStatus() != ChatUploadStatus.aborted)
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        ChatAttachment thumb = attachmentRepository.findByAttachmentId(thumbSession.getAttachmentId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        if (thumbSession.getStatus() == ChatUploadStatus.completed) {
            return thumbnailView(thumb, true);
        }
        if (!oss.exists(thumb.getObjectKey())) {
            failThumbnail(parent, thumbSession, thumb, false);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PART_MISSING");
        }
        byte[] bytes = oss.getBoundedBytes(thumb.getObjectKey(), props.thumbnailMaxBytes());
        if (!ChatAttachmentContentDisposition.isJpeg(bytes)) {
            failThumbnail(parent, thumbSession, thumb, true);
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        }
        BufferedImage image;
        try {
            image = imageProcessor.decode(bytes);
        } catch (Exception e) {
            failThumbnail(parent, thumbSession, thumb, true);
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        }
        int longEdge = Math.max(image.getWidth(), image.getHeight());
        if (longEdge > props.thumbnailMaxLongEdge() || bytes.length > props.thumbnailMaxBytes()) {
            failThumbnail(parent, thumbSession, thumb, true);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }
        Instant now = Instant.now();
        thumb.setSizeBytes((long) bytes.length);
        thumb.setWidth(image.getWidth());
        thumb.setHeight(image.getHeight());
        thumb.setStatus(ChatAttachmentStatus.ready);
        thumb.setReadyAt(now);
        thumb.setExpiresAt(now.plusSeconds(props.confirmedRetentionDays() * 86400L));
        thumb.setChecksumStatus(ChatChecksumStatus.unverified);
        attachmentRepository.save(thumb);
        quotaService.settle(userId, thumbSession.getQuotaDay(), thumbSession.getReservedStorageBytes(), bytes.length);
        thumbSession.setStatus(ChatUploadStatus.completed);
        sessionRepository.save(thumbSession);
        ChatAttachment parentAtt = attachmentRepository.findByAttachmentId(parent.getAttachmentId()).orElseThrow();
        parentAtt.setThumbnailAttachmentId(thumb.getAttachmentId());
        attachmentRepository.save(parentAtt);
        return thumbnailView(thumb, true);
    }

    void expire(ChatUploadSession session) {
        if (!OPEN.contains(session.getStatus())) {
            return;
        }
        int cas = sessionRepository.casStatus(session.getUploadId(), OPEN, ChatUploadStatus.expired);
        if (cas == 0) {
            return;
        }
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(session.getAttachmentId()).orElse(null);
        if (attachment != null) {
            oss.abortMultipart(attachment.getObjectKey(), session.getProviderUploadId());
            attachment.setStatus(ChatAttachmentStatus.deleted);
            attachmentRepository.save(attachment);
        }
        quotaService.releaseReserved(session.getOwnerUserId(), session.getQuotaDay(), session.getReservedStorageBytes());
    }

    private void failThumbnail(ChatUploadSession parent, ChatUploadSession thumbSession, ChatAttachment thumb,
                               boolean deleteObject) {
        thumbSession.setStatus(ChatUploadStatus.failed);
        sessionRepository.save(thumbSession);
        thumb.setStatus(ChatAttachmentStatus.rejected);
        attachmentRepository.save(thumb);
        quotaService.releaseReserved(thumb.getOwnerUserId(), thumbSession.getQuotaDay(),
            thumbSession.getReservedStorageBytes());
        if (deleteObject) {
            oss.deleteObject(thumb.getObjectKey());
        }
        ChatAttachment parentAtt = attachmentRepository.findByAttachmentId(parent.getAttachmentId()).orElse(null);
        if (parentAtt != null && thumb.getAttachmentId().equals(parentAtt.getThumbnailAttachmentId())) {
            parentAtt.setThumbnailAttachmentId(null);
            attachmentRepository.save(parentAtt);
        }
    }

    private List<ChatAttachmentOssClient.StoredPart> syncParts(ChatUploadSession session, ChatAttachment attachment) {
        List<ChatAttachmentOssClient.StoredPart> stored =
            oss.listParts(attachment.getObjectKey(), session.getProviderUploadId());
        Instant now = Instant.now();
        for (ChatAttachmentOssClient.StoredPart part : stored) {
            ChatUploadPart row = partRepository
                .findById(new ChatUploadPartId(session.getUploadId(), part.partNumber()))
                .orElseGet(ChatUploadPart::new);
            row.setUploadId(session.getUploadId());
            row.setPartNumber(part.partNumber());
            row.setSizeBytes(part.sizeBytes());
            row.setEtag(part.etag());
            row.setConfirmedAt(now);
            partRepository.save(row);
        }
        return stored;
    }

    private void validateParts(ChatUploadSession session, List<ChatAttachmentOssClient.StoredPart> stored) {
        if (stored.size() != session.getExpectedPartCount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PART_MISSING");
        }
        stored.sort(Comparator.comparingInt(ChatAttachmentOssClient.StoredPart::partNumber));
        long total = 0;
        for (int i = 0; i < stored.size(); i++) {
            ChatAttachmentOssClient.StoredPart part = stored.get(i);
            if (part.partNumber() != i + 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PART_MISSING");
            }
            boolean last = i == stored.size() - 1;
            if (!last && part.sizeBytes() != session.getPartSizeBytes()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH");
            }
            if (last && (part.sizeBytes() <= 0 || part.sizeBytes() > session.getPartSizeBytes())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH");
            }
            total += part.sizeBytes();
        }
        if (total != session.getDeclaredSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CHECKSUM_MISMATCH");
        }
    }

    private ChatUploadSession requireOwnedOpen(String userId, String uploadId) {
        ChatUploadSession session = requireOwned(userId, uploadId);
        if (session.getExpiresAt().isBefore(Instant.now())) {
            expire(session);
            throw new ResponseStatusException(HttpStatus.GONE, "UPLOAD_EXPIRED");
        }
        if (!OPEN.contains(session.getStatus()) && session.getStatus() != ChatUploadStatus.completing) {
            throw new ResponseStatusException(HttpStatus.GONE, "UPLOAD_EXPIRED");
        }
        return session;
    }

    private ChatUploadSession requireOwned(String userId, String uploadId) {
        ChatUploadSession session = sessionRepository.findByUploadIdAndOwnerUserId(uploadId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "UPLOAD_EXPIRED"));
        return session;
    }

    private boolean sameInitParams(ChatUploadSession session, InitRequest req,
                                   ChatAttachmentConversationIds.ConversationIdentity conv,
                                   ChatAttachmentKind kind, ChatNativeMessageKind nativeKind) {
        return session.getDeclaredSizeBytes() == req.declaredSizeBytes()
            && session.getKind() == kind
            && session.getNativeMessageKind() == nativeKind
            && session.getConversationKey().equals(conv.conversationKey())
            && Objects.equals(session.getGroupId(), conv.groupId());
    }

    private ChatAttachmentConversationIds.ConversationIdentity conversation(
        String userId, String type, String peerUserId, String groupId) {
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return switch (type.trim().toLowerCase()) {
            case "c2c" -> ChatAttachmentConversationIds.c2c(userId, peerUserId);
            case "group" -> ChatAttachmentConversationIds.group(groupId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        };
    }

    private ChatAttachmentKind parseKind(String raw) {
        try {
            return ChatAttachmentKind.valueOf(raw == null ? "" : raw.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    private ChatNativeMessageKind parseNative(String raw) {
        try {
            return ChatNativeMessageKind.valueOf(raw == null ? "" : raw.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    private Map<String, Object> initView(ChatUploadSession session) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadId", session.getUploadId());
        out.put("attachmentId", session.getAttachmentId());
        out.put("status", session.getStatus().name());
        out.put("partSizeBytes", session.getPartSizeBytes());
        out.put("expectedPartCount", session.getExpectedPartCount());
        out.put("maxParallelPartsPerUpload", props.maxParallelPartsPerUpload());
        out.put("expiresAt", session.getExpiresAt().toString());
        return out;
    }

    private Map<String, Object> thumbnailInitView(ChatUploadSession session) {
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(session.getAttachmentId()).orElseThrow();
        Instant expiresAt = oss.urlExpiresAt(props.uploadPartUrlTtlSeconds());
        String url = oss.presignPut(attachment.getObjectKey(), "image/jpeg", props.uploadPartUrlTtlSeconds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploadId", session.getUploadId());
        out.put("thumbnailAttachmentId", session.getAttachmentId());
        out.put("method", "PUT");
        out.put("url", url);
        out.put("headers", Map.of("Content-Type", "image/jpeg"));
        out.put("expiresAt", expiresAt.toString());
        out.put("maxBytes", props.thumbnailMaxBytes());
        return out;
    }

    private Map<String, Object> thumbnailView(ChatAttachment thumb, boolean bound) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("thumbnailAttachmentId", thumb.getAttachmentId());
        out.put("status", thumb.getStatus().name());
        out.put("bound", bound);
        out.put("width", thumb.getWidth());
        out.put("height", thumb.getHeight());
        out.put("sizeBytes", thumb.getSizeBytes());
        return out;
    }

    private Map<String, Object> attachmentView(String attachmentId) {
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(attachmentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ATTACHMENT_GONE"));
        return attachmentView(attachment);
    }

    Map<String, Object> attachmentView(ChatAttachment attachment) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attachmentId", attachment.getAttachmentId());
        out.put("status", attachment.getStatus().name());
        out.put("kind", attachment.getKind().name());
        out.put("nativeMessageKind", attachment.getNativeMessageKind().name());
        out.put("originalName", attachment.getOriginalName());
        out.put("mimeType", attachment.getMimeType());
        out.put("sizeBytes", attachment.getSizeBytes());
        out.put("declaredSizeBytes", attachment.getDeclaredSizeBytes());
        out.put("checksumStatus", attachment.getChecksumStatus().name());
        out.put("durationMs", attachment.getDurationMs());
        out.put("width", attachment.getWidth());
        out.put("height", attachment.getHeight());
        ChatMetadataProvenance source = attachment.getMediaProbeSource() != null
            ? attachment.getMediaProbeSource()
            : attachment.getMetadataProvenance();
        if (source == null) {
            out.put("mediaProbe", null);
        } else {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("source", source.name());
            probe.put("probedAt",
                attachment.getMediaProbedAt() == null ? null : attachment.getMediaProbedAt().toString());
            out.put("mediaProbe", probe);
        }
        out.put("metadataProvenance", source == null ? null : source.name());
        out.put("thumbnailAttachmentId", attachment.getThumbnailAttachmentId());
        out.put("readyAt", attachment.getReadyAt() == null ? null : attachment.getReadyAt().toString());
        out.put("expiresAt", attachment.getExpiresAt() == null ? null : attachment.getExpiresAt().toString());
        return out;
    }
}
