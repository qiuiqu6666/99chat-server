package com.chat99.server.chatattachment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatAttachmentAccessService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentAccessService.class);

    private final ChatAttachmentProperties props;
    private final ChatAttachmentOssClient oss;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatAttachmentReferenceRepository referenceRepository;
    private final ChatAttachmentAuthService authService;
    private final ChatAttachmentThumbnailExtractService thumbnailExtractService;

    public ChatAttachmentAccessService(ChatAttachmentProperties props,
                                       ChatAttachmentOssClient oss,
                                       ChatAttachmentRepository attachmentRepository,
                                       ChatAttachmentReferenceRepository referenceRepository,
                                       ChatAttachmentAuthService authService,
                                       ChatAttachmentThumbnailExtractService thumbnailExtractService) {
        this.props = props;
        this.oss = oss;
        this.attachmentRepository = attachmentRepository;
        this.referenceRepository = referenceRepository;
        this.authService = authService;
        this.thumbnailExtractService = thumbnailExtractService;
    }

    public record AccessRequest(String referenceId, String purpose) {}

    public Map<String, Object> access(String userId, String attachmentId, AccessRequest req) {
        String requestId = ChatAttachmentAccessLogFilter.currentRequestId();
        authService.requireCanRead();
        if (req == null || req.referenceId() == null || req.referenceId().isBlank()) {
            return fail(requestId, attachmentId, req, HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        ChatAttachmentReference ref = referenceRepository.findByReferenceId(req.referenceId().trim())
            .orElse(null);
        if (ref == null) {
            return fail(requestId, attachmentId, req, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (!attachmentId.equals(ref.getAttachmentId())) {
            return fail(requestId, attachmentId, req, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        try {
            authorizeReference(userId, ref);
        } catch (ResponseStatusException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == null) {
                status = HttpStatus.FORBIDDEN;
            }
            return fail(requestId, attachmentId, req, status,
                e.getReason() == null ? "ACCESS_DENIED" : e.getReason());
        }
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(attachmentId).orElse(null);
        if (attachment == null) {
            return fail(requestId, attachmentId, req, HttpStatus.GONE, "ATTACHMENT_GONE");
        }
        if (attachment.getStatus() != ChatAttachmentStatus.ready) {
            if (attachment.getStatus() == ChatAttachmentStatus.deleted
                || attachment.getStatus() == ChatAttachmentStatus.deleting) {
                return fail(requestId, attachmentId, req, HttpStatus.GONE, "ATTACHMENT_GONE");
            }
            return fail(requestId, attachmentId, req, HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY");
        }
        ChatAccessPurpose purpose = parsePurpose(req.purpose());
        ChatAttachment target = attachment;
        if (purpose == ChatAccessPurpose.thumbnail) {
            ChatAttachment thumb = resolveThumbnail(attachment);
            if (thumb == null) {
                return fail(requestId, attachmentId, req, HttpStatus.GONE, "ATTACHMENT_GONE");
            }
            target = thumb;
        }
        if (target.getObjectKey() == null || !oss.exists(target.getObjectKey())) {
            return fail(requestId, attachmentId, req, HttpStatus.GONE, "ATTACHMENT_GONE");
        }
        String disposition = null;
        if (purpose == ChatAccessPurpose.download) {
            disposition = ChatAttachmentContentDisposition.attachment(target.getOriginalName() != null
                ? target.getOriginalName() : attachment.getOriginalName());
        }
        String url = oss.presignGet(
            target.getObjectKey(),
            props.accessUrlTtlSeconds(),
            target.getMimeType(),
            disposition);
        Instant expiresAt = oss.urlExpiresAt(props.accessUrlTtlSeconds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", "GET");
        out.put("url", url);
        out.put("expiresAt", expiresAt.toString());
        out.put("contentType", target.getMimeType());
        out.put("sizeBytes", target.getSizeBytes());
        out.put("rangeSupported", true);
        out.put("headers", Map.of());
        if (purpose == ChatAccessPurpose.thumbnail) {
            out.put("thumbnailAttachmentId", target.getAttachmentId());
        }
        if (requestId != null) {
            out.put("requestId", requestId);
        }
        log.info("chat-att access requestId={} attachmentId={} purpose={} code=OK http=200 objectExists=true",
            requestId, attachmentId, purpose.name());
        return out;
    }

    private ChatAttachment resolveThumbnail(ChatAttachment video) {
        if (video.getThumbnailAttachmentId() != null) {
            ChatAttachment bound = attachmentRepository.findByAttachmentId(video.getThumbnailAttachmentId())
                .orElse(null);
            if (bound != null && bound.getStatus() == ChatAttachmentStatus.ready) {
                return bound;
            }
        }
        ChatAttachment extracted = thumbnailExtractService.ensureThumbnail(video.getAttachmentId());
        if (extracted != null && extracted.getStatus() == ChatAttachmentStatus.ready) {
            return extracted;
        }
        return null;
    }

    private <T> T fail(String requestId, String attachmentId, AccessRequest req,
                       HttpStatus status, String code) {
        String purpose = req == null || req.purpose() == null ? "" : req.purpose();
        log.info("chat-att access requestId={} attachmentId={} purpose={} code={} http={}",
            requestId, attachmentId, purpose, code, status.value());
        throw new ResponseStatusException(status, code);
    }

    private void authorizeReference(String userId, ChatAttachmentReference ref) {
        if (ref.getState() == ChatReferenceState.revoked || ref.getState() == ChatReferenceState.expired) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (ref.getState() == ChatReferenceState.manualRequired && (ref.getProviderMessageId() == null
            || ref.getProviderMessageId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (ref.getState() == ChatReferenceState.reserved
            && ref.getExpiresAt() != null && ref.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (ref.getConversationType() == ChatConversationType.c2c) {
            if (!authService.isC2cParticipant(userId, ref)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            }
            return;
        }
        authService.requireGroupMember(ref.getGroupId(), userId);
    }

    private static ChatAccessPurpose parsePurpose(String raw) {
        if (raw == null || raw.isBlank()) {
            return ChatAccessPurpose.download;
        }
        try {
            return ChatAccessPurpose.valueOf(raw.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }
}
