package com.chat99.server.chatattachment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatAttachmentReferenceService {

    private final ChatAttachmentProperties props;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatAttachmentReferenceRepository referenceRepository;
    private final ChatUploadSessionRepository sessionRepository;
    private final ChatAttachmentAuthService authService;

    public ChatAttachmentReferenceService(ChatAttachmentProperties props,
                                          ChatAttachmentRepository attachmentRepository,
                                          ChatAttachmentReferenceRepository referenceRepository,
                                          ChatUploadSessionRepository sessionRepository,
                                          ChatAttachmentAuthService authService) {
        this.props = props;
        this.attachmentRepository = attachmentRepository;
        this.referenceRepository = referenceRepository;
        this.sessionRepository = sessionRepository;
        this.authService = authService;
    }

    public record CreateRequest(
        String clientOperationId,
        String conversationType,
        String peerUserId,
        String groupId
    ) {}

    @Transactional
    public Map<String, Object> create(String userId, String attachmentId, CreateRequest req, boolean callerCapable) {
        authService.requireCanSend(callerCapable);
        if (req == null || req.clientOperationId() == null || req.clientOperationId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(attachmentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ATTACHMENT_GONE"));
        if (!userId.equals(attachment.getOwnerUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (attachment.getStatus() != ChatAttachmentStatus.ready) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY");
        }
        var conv = switch (req.conversationType() == null ? "" : req.conversationType().trim().toLowerCase()) {
            case "c2c" -> ChatAttachmentConversationIds.c2c(userId, req.peerUserId());
            case "group" -> ChatAttachmentConversationIds.group(req.groupId());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        };
        authService.requireSendConversation(userId, conv);
        ChatUploadSession upload = sessionRepository
            .findFirstByAttachmentIdAndParentUploadIdIsNull(attachmentId)
            .orElse(null);
        if (upload != null && !upload.getConversationKey().equals(conv.conversationKey())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CONVERSATION_FORBIDDEN");
        }
        var existing = referenceRepository.findByOwnerUserIdAndClientOperationIdAndAttachmentId(
            userId, req.clientOperationId().trim(), attachmentId);
        if (existing.isPresent()) {
            ChatAttachmentReference ref = existing.get();
            if (!ref.getConversationKey().equals(conv.conversationKey())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            return view(ref);
        }
        Instant now = Instant.now();
        ChatAttachmentReference ref = new ChatAttachmentReference();
        ref.setReferenceId(ChatAttachmentIds.reference());
        ref.setAttachmentId(attachmentId);
        ref.setOwnerUserId(userId);
        ref.setConversationType(conv.type());
        ref.setConversationKey(conv.conversationKey());
        ref.setParticipantLow(conv.participantLow());
        ref.setParticipantHigh(conv.participantHigh());
        ref.setGroupId(conv.groupId());
        ref.setClientOperationId(req.clientOperationId().trim());
        ref.setReferenceType(ChatReferenceType.message);
        ref.setState(ChatReferenceState.reserved);
        ref.setCreatedAt(now);
        ref.setExpiresAt(now.plusSeconds(props.reservedReferenceTtlSeconds()));
        try {
            referenceRepository.saveAndFlush(ref);
        } catch (DataIntegrityViolationException dup) {
            ChatAttachmentReference raced = referenceRepository
                .findByOwnerUserIdAndClientOperationIdAndAttachmentId(
                    userId, req.clientOperationId().trim(), attachmentId)
                .orElseThrow(() -> dup);
            if (!raced.getConversationKey().equals(conv.conversationKey())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            }
            return view(raced);
        }
        return view(ref);
    }

    private Map<String, Object> view(ChatAttachmentReference ref) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("referenceId", ref.getReferenceId());
        out.put("attachmentId", ref.getAttachmentId());
        out.put("state", ref.getState().name());
        out.put("conversationType", ref.getConversationType().name());
        out.put("expiresAt", ref.getExpiresAt() == null ? null : ref.getExpiresAt().toString());
        return out;
    }
}
