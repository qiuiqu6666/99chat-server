package com.chat99.server.chatattachment;

import com.chat99.server.im.ImUserIdService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatAttachmentImBindService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentImBindService.class);

    private final ChatAttachmentReferenceRepository referenceRepository;
    private final ChatNativeVideoMessageRepository nativeVideoMessageRepository;
    private final ImUserIdService imUserIdService;
    private final ObjectMapper json;

    public ChatAttachmentImBindService(ChatAttachmentReferenceRepository referenceRepository,
                                       ChatNativeVideoMessageRepository nativeVideoMessageRepository,
                                       ImUserIdService imUserIdService,
                                       ObjectMapper json) {
        this.referenceRepository = referenceRepository;
        this.nativeVideoMessageRepository = nativeVideoMessageRepository;
        this.imUserIdService = imUserIdService;
        this.json = json;
    }

    public void handleAfterSendRaw(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = json.readValue(rawBody, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            handleAfterSend(body);
        } catch (Exception e) {
            log.warn("chat attachment im event parse failed err={}", e.getMessage());
        }
    }

    @Transactional
    public void handleAfterSend(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        var messages = ChatAttachmentCustomElemSupport.extract(body, json);
        var nativeVideo = ChatAttachmentCustomElemSupport.extractNativeVideo(body, json);
        if (messages.isEmpty() && nativeVideo == null) {
            return;
        }
        String fromUser = imUserIdService.findBusinessUserId(str(body.get("From_Account"))).orElse(null);
        String msgKey = str(body.get("MsgKey"));
        String msgId = firstNonBlank(msgKey, str(body.get("MsgId")), str(body.get("MsgSeq")));
        Long msgSeq = asLong(body.get("MsgSeq"));
        String toIm = str(body.get("To_Account"));
        String groupId = str(body.get("GroupId"));
        if (nativeVideo != null) {
            bindNativeVideo(nativeVideo, fromUser, toIm, groupId, msgKey, msgSeq);
        }
        for (var msg : messages) {
            bindOne(msg.attachmentId(), msg.referenceId(), fromUser, toIm, groupId, msgId);
        }
    }

    private void bindNativeVideo(ChatAttachmentCustomElemSupport.NativeVideoMessage msg,
                                 String fromUser, String toIm, String groupId,
                                 String msgKey, Long msgSeq) {
        String providerId = firstNonBlank(msgKey, msgSeq == null ? null : String.valueOf(msgSeq));
        bindOne(msg.attachmentId(), msg.referenceId(), fromUser, toIm, groupId, providerId);
        if (fromUser == null || msg.clientOperationId() == null) {
            return;
        }
        ChatNativeVideoMessage row = nativeVideoMessageRepository
            .findBySenderUserIdAndClientOperationId(fromUser, msg.clientOperationId())
            .orElse(null);
        if (row == null || row.getStatus() == ChatNativeVideoStatus.sent) {
            return;
        }
        if (!msg.attachmentId().equals(row.getAttachmentId())
            || !msg.referenceId().equals(row.getReferenceId())) {
            log.warn("chat native-video bind identity mismatch clientOperationId={}", msg.clientOperationId());
            return;
        }
        Instant now = Instant.now();
        row.setStatus(ChatNativeVideoStatus.sent);
        if (msgKey != null && !msgKey.isBlank()) {
            row.setMsgKey(msgKey);
        }
        if (msgSeq != null && msgSeq > 0) {
            row.setMsgSeq(msgSeq);
        }
        row.setFailCode(null);
        row.setLockUntil(null);
        row.setSentAt(now);
        row.setUpdatedAt(now);
        nativeVideoMessageRepository.save(row);
        log.info("chat native-video confirmed clientOperationId={} attachmentId={}",
            msg.clientOperationId(), msg.attachmentId());
    }

    private void bindOne(String attachmentId, String referenceId,
                         String fromUser, String toIm, String groupId, String msgId) {
        ChatAttachmentReference ref = referenceRepository.findByReferenceId(referenceId).orElse(null);
        if (ref == null) {
            log.warn("chat attachment bind missing referenceId={}", referenceId);
            return;
        }
        if (fromUser == null || !fromUser.equals(ref.getOwnerUserId())) {
            log.warn("chat attachment bind sender mismatch referenceId={}", referenceId);
            return;
        }
        if (!attachmentId.equals(ref.getAttachmentId())) {
            log.warn("chat attachment bind attachment mismatch referenceId={}", referenceId);
            return;
        }
        boolean sessionOk;
        if (ref.getConversationType() == ChatConversationType.c2c) {
            String toUser = imUserIdService.findBusinessUserId(toIm).orElse(null);
            sessionOk = toUser != null && ref.getConversationKey().equals(
                ChatAttachmentConversationIds.c2c(fromUser, toUser).conversationKey());
        } else {
            sessionOk = groupId != null && groupId.equals(ref.getGroupId());
        }
        if (!sessionOk) {
            log.warn("chat attachment bind conversation mismatch referenceId={}", referenceId);
            return;
        }
        if (ref.getState() == ChatReferenceState.confirmed) {
            return;
        }
        if (ref.getState() != ChatReferenceState.reserved
            && ref.getState() != ChatReferenceState.manualRequired) {
            return;
        }
        Instant now = Instant.now();
        ref.setProviderMessageId(msgId);
        ref.setState(ChatReferenceState.confirmed);
        ref.setConfirmedAt(now);
        ref.setExpiresAt(null);
        referenceRepository.save(ref);
        log.info("chat attachment confirmed referenceId={} attachmentId={}",
            ref.getReferenceId(), ref.getAttachmentId());
    }

    public Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ActionStatus", "OK");
        out.put("ErrorCode", 0);
        out.put("ErrorInfo", "");
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
