package com.chat99.server.chatattachment;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImRestException;
import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatNativeVideoService {

    private static final Logger log = LoggerFactory.getLogger(ChatNativeVideoService.class);

    public record SendRequest(
        String clientOperationId,
        String attachmentId,
        String referenceId,
        String conversationType,
        String peerUserId,
        String groupId,
        Long durationMs
    ) {}

    public record Prep(ChatNativeVideoMessage row, boolean deliverNow) {}

    private final ChatAttachmentProperties props;
    private final ChatAttachmentAuthService authService;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatAttachmentReferenceRepository referenceRepository;
    private final ChatNativeVideoMessageRepository messageRepository;
    private final ChatNativeVideoPersistence persistence;
    private final ChatNativeVideoMediaService mediaService;
    private final ChatAttachmentOssClient oss;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final ChatMediaProbeService mediaProbeService;
    private final ChatNativeVideoDurationMetrics durationMetrics;
    private final ObjectMapper json;

    public ChatNativeVideoService(ChatAttachmentProperties props,
                                  ChatAttachmentAuthService authService,
                                  ChatAttachmentRepository attachmentRepository,
                                  ChatAttachmentReferenceRepository referenceRepository,
                                  ChatNativeVideoMessageRepository messageRepository,
                                  ChatNativeVideoPersistence persistence,
                                  ChatNativeVideoMediaService mediaService,
                                  ChatAttachmentOssClient oss,
                                  ImAdminClient imAdmin,
                                  ImUserIdService imUserIdService,
                                  ChatMediaProbeService mediaProbeService,
                                  ChatNativeVideoDurationMetrics durationMetrics,
                                  ObjectMapper json) {
        this.props = props;
        this.authService = authService;
        this.attachmentRepository = attachmentRepository;
        this.referenceRepository = referenceRepository;
        this.messageRepository = messageRepository;
        this.persistence = persistence;
        this.mediaService = mediaService;
        this.oss = oss;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.mediaProbeService = mediaProbeService;
        this.durationMetrics = durationMetrics;
        this.json = json;
    }

    public Map<String, Object> get(String userId, String clientOperationId) {
        if (clientOperationId == null || clientOperationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND");
        }
        ChatNativeVideoMessage row = messageRepository
            .findBySenderUserIdAndClientOperationId(userId, clientOperationId.trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        return view(row);
    }

    public Map<String, Object> viewByOperationId(String operationId) {
        ChatNativeVideoMessage row = messageRepository.findById(operationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        return view(row);
    }

    public void requireEnabled(boolean callerCapable) {
        authService.requireCanSend(callerCapable);
        if (!props.nativeVideoMessageEnabled() || props.emergencyDisabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ATTACHMENT_DISABLED");
        }
        if (props.mediaHmacSecret() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE");
        }
    }

    public String resolveMediaBaseUrl(jakarta.servlet.http.HttpServletRequest request) {
        return mediaService.resolvePublicBaseUrl(request);
    }

    @Transactional
    public Prep prepare(String userId, SendRequest req, String mediaBaseUrl) {
        if (req == null || isBlank(req.clientOperationId()) || isBlank(req.attachmentId())
            || isBlank(req.referenceId()) || isBlank(req.conversationType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String op = req.clientOperationId().trim();
        String attachmentId = req.attachmentId().trim();
        String referenceId = req.referenceId().trim();
        var conv = switch (req.conversationType().trim().toLowerCase(Locale.ROOT)) {
            case "c2c" -> ChatAttachmentConversationIds.c2c(userId, req.peerUserId());
            case "group" -> ChatAttachmentConversationIds.group(req.groupId());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        };
        authService.requireSendConversation(userId, conv);
        ChatAttachment video = requireReadyVideo(userId, attachmentId);
        applyClientDuration(video, req.durationMs());
        ChatAttachment thumb = requireReadyThumb(video);
        ChatAttachmentReference ref = referenceRepository.findByReferenceId(referenceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED"));
        if (!userId.equals(ref.getOwnerUserId())
            || !attachmentId.equals(ref.getAttachmentId())
            || !ref.getConversationKey().equals(conv.conversationKey())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_FORBIDDEN");
        }
        if (ref.getState() != ChatReferenceState.reserved && ref.getState() != ChatReferenceState.confirmed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (!oss.exists(video.getObjectKey()) || !oss.exists(thumb.getObjectKey())) {
            throw new ResponseStatusException(HttpStatus.GONE, "ATTACHMENT_GONE");
        }
        if (isBlank(mediaBaseUrl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String origin = mediaBaseUrl.trim().replaceAll("/+$", "");
        Instant now = Instant.now();
        ChatNativeVideoMessage existing = messageRepository.findForUpdate(userId, op).orElse(null);
        if (existing != null) {
            if (!sameTarget(existing, attachmentId, referenceId, conv)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            if (existing.getStatus() == ChatNativeVideoStatus.sent) {
                return new Prep(existing, false);
            }
            if (existing.getStatus() == ChatNativeVideoStatus.failed) {
                existing.setImRandom(newRandom());
                existing.setFailCode(null);
                existing.setStatus(ChatNativeVideoStatus.pending);
                existing.setMediaBaseUrl(origin);
                existing.setLockUntil(now.plus(ChatNativeVideoPersistence.LOCK_TTL));
                existing.setUpdatedAt(now);
                return new Prep(messageRepository.save(existing), true);
            }
            if (existing.getLockUntil() != null && existing.getLockUntil().isAfter(now)) {
                return new Prep(existing, false);
            }
            existing.setMediaBaseUrl(origin);
            existing.setLockUntil(now.plus(ChatNativeVideoPersistence.LOCK_TTL));
            existing.setUpdatedAt(now);
            return new Prep(messageRepository.save(existing), true);
        }
        ChatNativeVideoMessage created = new ChatNativeVideoMessage();
        created.setOperationId(ChatAttachmentIds.nativeVideoOperation());
        created.setSenderUserId(userId);
        created.setClientOperationId(op);
        created.setAttachmentId(attachmentId);
        created.setReferenceId(referenceId);
        created.setConversationType(conv.type());
        created.setConversationKey(conv.conversationKey());
        created.setPeerUserId(conv.type() == ChatConversationType.c2c
            ? authService.peerOf(userId, conv) : null);
        created.setGroupId(conv.groupId());
        created.setStatus(ChatNativeVideoStatus.pending);
        created.setImRandom(newRandom());
        created.setMediaBaseUrl(origin);
        created.setLockUntil(now.plus(ChatNativeVideoPersistence.LOCK_TTL));
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        try {
            return new Prep(messageRepository.saveAndFlush(created), true);
        } catch (DataIntegrityViolationException dup) {
            ChatNativeVideoMessage raced = messageRepository.findForUpdate(userId, op)
                .orElseThrow(() -> dup);
            if (!sameTarget(raced, attachmentId, referenceId, conv)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            return new Prep(raced, raced.getStatus() != ChatNativeVideoStatus.sent
                && (raced.getLockUntil() == null || !raced.getLockUntil().isAfter(Instant.now())));
        }
    }

    public void deliver(String operationId) {
        ChatNativeVideoMessage row = messageRepository.findById(operationId).orElse(null);
        if (row == null || row.getStatus() == ChatNativeVideoStatus.sent) {
            return;
        }
        ChatAttachment video = attachmentRepository.findByAttachmentId(row.getAttachmentId()).orElse(null);
        if (video == null) {
            persistence.mark(operationId, ChatNativeVideoStatus.failed, "ATTACHMENT_GONE", null, null);
            return;
        }
        video = resolveDuration(video);
        ChatAttachment thumb = requireReadyThumbOrNull(video);
        if (thumb == null) {
            persistence.mark(operationId, ChatNativeVideoStatus.failed, "ATTACHMENT_NOT_READY", null, null);
            return;
        }
        if (isBlank(row.getMediaBaseUrl())) {
            persistence.mark(operationId, ChatNativeVideoStatus.failed, "INVALID_INPUT", null, null);
            return;
        }
        oss.setPublicRead(video.getObjectKey());
        oss.setPublicRead(thumb.getObjectKey());
        Map<String, Object> content = videoContent(video, thumb, row.getMediaBaseUrl());
        ChatMetadataProvenance source = ChatMediaMetadata.effectiveSource(video);
        durationMetrics.record(source);
        boolean durationUnknown = ChatMediaMetadata.sanitizeDurationMs(video.getDurationMs()) == null;
        String cloud;
        try {
            cloud = json.writeValueAsString(cloudCustom(row, false));
        } catch (Exception e) {
            persistence.mark(operationId, ChatNativeVideoStatus.failed, "INVALID_INPUT", null, null);
            return;
        }
        boolean c2c = row.getConversationType() == ChatConversationType.c2c;
        String from = imUserIdService.requireImUserId(row.getSenderUserId());
        String to = c2c
            ? imUserIdService.requireImUserId(row.getPeerUserId())
            : row.getGroupId();
        try {
            ImAdminClient.NativeVideoSendResult result;
            try {
                result = imAdmin.sendNativeVideo(
                    c2c, from, to, row.getImRandom(), content, cloud);
            } catch (ImRestException first) {
                if (durationUnknown && !isUnknownIm(first) && Long.valueOf(0L).equals(asLong(content.get("VideoSecond")))) {
                    log.warn("native video VideoSecond=0 rejected operationId={} code={} retrying with 1",
                        operationId, first.imErrorCode());
                    content.put("VideoSecond", 1L);
                    try {
                        cloud = json.writeValueAsString(cloudCustom(row, true));
                    } catch (Exception e) {
                        persistence.mark(operationId, ChatNativeVideoStatus.failed, "INVALID_INPUT", null, null);
                        return;
                    }
                    result = imAdmin.sendNativeVideo(
                        c2c, from, to, row.getImRandom(), content, cloud);
                } else {
                    throw first;
                }
            }
            if (!result.accepted()) {
                persistence.mark(operationId, ChatNativeVideoStatus.unknown, "IM_ERROR",
                    result.msgKey(), result.msgSeq());
                return;
            }
            if (c2c && (result.msgKey() == null || result.msgKey().isBlank())) {
                persistence.mark(operationId, ChatNativeVideoStatus.unknown, "IM_MISSING_MSGKEY",
                    result.msgKey(), result.msgSeq());
                return;
            }
            if (!c2c && (result.msgSeq() == null || result.msgSeq() <= 0)) {
                persistence.mark(operationId, ChatNativeVideoStatus.unknown, "IM_MISSING_MSGSEQ",
                    result.msgKey(), result.msgSeq());
                return;
            }
            persistence.markSent(operationId, result.msgKey(), result.msgSeq());
        } catch (ImRestException e) {
            if (isUnknownIm(e)) {
                log.warn("native video IM unknown operationId={} code={}", operationId, e.imErrorCode());
                persistence.mark(operationId, ChatNativeVideoStatus.unknown, "IM_REST_UNAVAILABLE", null, null);
            } else {
                log.warn("native video IM rejected operationId={} code={}", operationId, e.imErrorCode());
                persistence.mark(operationId, ChatNativeVideoStatus.failed, "IM_ERROR", null, null);
            }
        } catch (RuntimeException e) {
            log.warn("native video IM failed operationId={} err={}", operationId, e.getMessage());
            persistence.mark(operationId, ChatNativeVideoStatus.unknown, "IM_REST_UNAVAILABLE", null, null);
        }
    }

    public int resumeDue(int limit) {
        if (!props.nativeVideoMessageEnabled() || props.emergencyDisabled() || props.mediaHmacSecret() == null) {
            return 0;
        }
        List<ChatNativeVideoMessage> due = messageRepository.findDue(
            EnumSet.of(ChatNativeVideoStatus.pending, ChatNativeVideoStatus.unknown),
            Instant.now(),
            PageRequest.of(0, Math.max(1, limit)));
        int n = 0;
        for (ChatNativeVideoMessage row : due) {
            if (!persistence.tryAcquireLock(row.getOperationId())) {
                continue;
            }
            deliver(row.getOperationId());
            n++;
        }
        return n;
    }

    private ChatAttachment requireReadyVideo(String userId, String attachmentId) {
        ChatAttachment video = attachmentRepository.findByAttachmentId(attachmentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ATTACHMENT_GONE"));
        if (!userId.equals(video.getOwnerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (video.getKind() != ChatAttachmentKind.video) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (video.getStatus() != ChatAttachmentStatus.ready) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY");
        }
        return video;
    }

    private ChatAttachment requireReadyThumb(ChatAttachment video) {
        ChatAttachment thumb = requireReadyThumbOrNull(video);
        if (thumb == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY");
        }
        return thumb;
    }

    private ChatAttachment requireReadyThumbOrNull(ChatAttachment video) {
        if (video.getThumbnailAttachmentId() == null) {
            return null;
        }
        ChatAttachment thumb = attachmentRepository.findByAttachmentId(video.getThumbnailAttachmentId())
            .orElse(null);
        if (thumb == null || thumb.getStatus() != ChatAttachmentStatus.ready
            || thumb.getSizeBytes() == null || thumb.getSizeBytes() <= 0
            || thumb.getWidth() == null || thumb.getHeight() == null
            || thumb.getWidth() <= 0 || thumb.getHeight() <= 0) {
            return null;
        }
        return thumb;
    }

    private Map<String, Object> videoContent(ChatAttachment video, ChatAttachment thumb, String mediaBaseUrl) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("VideoUrl", mediaService.videoUrl(video, mediaBaseUrl));
        content.put("VideoUUID", video.getAttachmentId());
        content.put("VideoSize", video.getSizeBytes() == null ? video.getDeclaredSizeBytes() : video.getSizeBytes());
        content.put("VideoSecond", ChatMediaMetadata.videoSecond(video.getDurationMs()));
        content.put("VideoFormat", videoFormat(video));
        content.put("VideoDownloadFlag", 2);
        content.put("ThumbUrl", mediaService.thumbUrl(thumb, mediaBaseUrl));
        content.put("ThumbUUID", thumb.getAttachmentId());
        content.put("ThumbSize", thumb.getSizeBytes());
        content.put("ThumbWidth", thumb.getWidth());
        content.put("ThumbHeight", thumb.getHeight());
        content.put("ThumbFormat", thumbFormat(thumb));
        content.put("ThumbDownloadFlag", 2);
        return content;
    }

    private Map<String, Object> cloudCustom(ChatNativeVideoMessage row, boolean durationUnknown) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "chat.native-video");
        data.put("version", 1);
        data.put("clientOperationId", row.getClientOperationId());
        data.put("attachmentId", row.getAttachmentId());
        data.put("referenceId", row.getReferenceId());
        if (durationUnknown) {
            data.put("chatAttachment", Map.of("durationUnknown", true));
        }
        return data;
    }

    private ChatAttachment applyClientDuration(ChatAttachment video, Long durationMs) {
        Long sanitized = ChatMediaMetadata.sanitizeDurationMs(durationMs);
        if (sanitized == null) {
            return video;
        }
        video.setDurationMs(sanitized);
        ChatMediaMetadata.markSource(video, ChatMetadataProvenance.client);
        return attachmentRepository.save(video);
    }

    private ChatAttachment resolveDuration(ChatAttachment video) {
        ChatAttachment filled = mediaProbeService.fillIfMissing(video);
        if (ChatMediaMetadata.sanitizeDurationMs(filled.getDurationMs()) != null) {
            return filled;
        }
        ChatMediaMetadata.markSource(filled, ChatMetadataProvenance.fallback);
        return attachmentRepository.save(filled);
    }

    private static Long asLong(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static String thumbFormat(ChatAttachment thumb) {
        String name = thumb.getOriginalName() == null ? "" : thumb.getOriginalName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "png";
        }
        String mime = thumb.getMimeType() == null ? "" : thumb.getMimeType().toLowerCase(Locale.ROOT);
        if (mime.contains("png")) {
            return "png";
        }
        return "jpg";
    }

    private Map<String, Object> view(ChatNativeVideoMessage row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clientOperationId", row.getClientOperationId());
        out.put("attachmentId", row.getAttachmentId());
        out.put("referenceId", row.getReferenceId());
        out.put("status", row.getStatus().name());
        if (row.getStatus() == ChatNativeVideoStatus.sent) {
            out.put("messageType", "TIMVideoFileElem");
            if (row.getMsgKey() != null) {
                out.put("msgKey", row.getMsgKey());
            }
            if (row.getMsgSeq() != null) {
                out.put("msgSeq", row.getMsgSeq());
            }
        }
        return out;
    }

    private static boolean sameTarget(ChatNativeVideoMessage row, String attachmentId, String referenceId,
                                      ChatAttachmentConversationIds.ConversationIdentity conv) {
        return row.getAttachmentId().equals(attachmentId)
            && row.getReferenceId().equals(referenceId)
            && row.getConversationKey().equals(conv.conversationKey());
    }

    private static boolean isUnknownIm(ImRestException e) {
        int code = e.imErrorCode();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return code == 0
            || code == 60008
            || code == 60014
            || "IM_REST_UNAVAILABLE".equals(msg)
            || "IM_NOT_CONFIGURED".equals(msg);
    }

    private static String videoFormat(ChatAttachment video) {
        String name = video.getOriginalName() == null ? "" : video.getOriginalName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).replaceAll("[^a-z0-9]", "");
            if (!ext.isBlank()) {
                return ext;
            }
        }
        String mime = video.getMimeType() == null ? "" : video.getMimeType().toLowerCase(Locale.ROOT);
        if (mime.contains("quicktime") || mime.contains("mov")) {
            return "mov";
        }
        return "mp4";
    }

    private static int newRandom() {
        return ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
