package com.chat99.server.chatattachment;

import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ChatAttachmentSendGuardService {

    public static final String REJECT_CODE = "ATTACHMENT_ACCESS_DENIED";

    private final ChatAttachmentReferenceRepository referenceRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ImUserIdService imUserIdService;
    private final ObjectMapper json;

    public ChatAttachmentSendGuardService(ChatAttachmentReferenceRepository referenceRepository,
                                          ChatAttachmentRepository attachmentRepository,
                                          ImUserIdService imUserIdService,
                                          ObjectMapper json) {
        this.referenceRepository = referenceRepository;
        this.attachmentRepository = attachmentRepository;
        this.imUserIdService = imUserIdService;
        this.json = json;
    }

    public Optional<String> evaluate(Map<String, Object> body) {
        var messages = ChatAttachmentCustomElemSupport.extract(body, json);
        if (messages.isEmpty()) {
            return Optional.empty();
        }
        String fromIm = str(body.get("From_Account"));
        String fromUser = imUserIdService.findBusinessUserId(fromIm).orElse(null);
        String toIm = str(body.get("To_Account"));
        String groupId = str(body.get("GroupId"));
        for (var msg : messages) {
            String reject = validateOne(msg, fromUser, toIm, groupId, body);
            if (reject != null) {
                return Optional.of(reject);
            }
        }
        return Optional.empty();
    }

    private String validateOne(ChatAttachmentCustomElemSupport.AttachmentMessage msg,
                               String fromUser, String toIm, String groupId, Map<String, Object> body) {
        if (fromUser == null) {
            return REJECT_CODE;
        }
        if (jsonSizeTooLarge(body)) {
            return REJECT_CODE;
        }
        ChatAttachmentReference ref = referenceRepository.findByReferenceId(msg.referenceId()).orElse(null);
        if (ref == null || !msg.attachmentId().equals(ref.getAttachmentId())) {
            return REJECT_CODE;
        }
        if (!fromUser.equals(ref.getOwnerUserId())) {
            return REJECT_CODE;
        }
        if (ref.getState() != ChatReferenceState.reserved && ref.getState() != ChatReferenceState.confirmed) {
            return REJECT_CODE;
        }
        ChatAttachment attachment = attachmentRepository.findByAttachmentId(ref.getAttachmentId()).orElse(null);
        if (attachment == null || attachment.getStatus() != ChatAttachmentStatus.ready) {
            return REJECT_CODE;
        }
        if (ref.getConversationType() == ChatConversationType.c2c) {
            String toUser = imUserIdService.findBusinessUserId(toIm).orElse(null);
            if (toUser == null || !ref.getConversationKey().equals(
                ChatAttachmentConversationIds.c2c(fromUser, toUser).conversationKey())) {
                return REJECT_CODE;
            }
        } else {
            if (groupId == null || !groupId.equals(ref.getGroupId())) {
                return REJECT_CODE;
            }
        }
        return null;
    }

    private boolean jsonSizeTooLarge(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body.get("MsgBody")).getBytes(StandardCharsets.UTF_8).length
                > ChatAttachmentCustomElemSupport.MAX_DATA_BYTES + 2048;
        } catch (Exception e) {
            return false;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
