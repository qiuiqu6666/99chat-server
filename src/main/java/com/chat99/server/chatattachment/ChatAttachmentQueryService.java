package com.chat99.server.chatattachment;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatAttachmentQueryService {

    private static final Set<ChatReferenceState> VIEW_STATES = EnumSet.of(
        ChatReferenceState.reserved, ChatReferenceState.confirmed, ChatReferenceState.manualRequired);

    private final ChatAttachmentRepository attachmentRepository;
    private final ChatAttachmentReferenceRepository referenceRepository;
    private final ChatUploadService uploadService;

    public ChatAttachmentQueryService(ChatAttachmentRepository attachmentRepository,
                                      ChatAttachmentReferenceRepository referenceRepository,
                                      ChatUploadService uploadService) {
        this.attachmentRepository = attachmentRepository;
        this.referenceRepository = referenceRepository;
        this.uploadService = uploadService;
    }

    public Map<String, Object> get(String userId, String attachmentId) {
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(attachmentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ATTACHMENT_GONE"));
        if (attachment.getStatus() == ChatAttachmentStatus.deleted
            || attachment.getStatus() == ChatAttachmentStatus.deleting) {
            throw new ResponseStatusException(HttpStatus.GONE, "ATTACHMENT_GONE");
        }
        if (!userId.equals(attachment.getOwnerUserId())) {
            List<ChatAttachmentReference> refs = referenceRepository.findAccessibleByAttachment(
                attachmentId, userId, VIEW_STATES);
            if (refs.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCESS_DENIED");
            }
        }
        return uploadService.attachmentView(attachment);
    }
}
