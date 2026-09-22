package com.chat99.server.chatattachment;

import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatNativeVideoPersistence {

    static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final ChatNativeVideoMessageRepository messageRepository;
    private final ChatAttachmentReferenceRepository referenceRepository;

    public ChatNativeVideoPersistence(ChatNativeVideoMessageRepository messageRepository,
                                      ChatAttachmentReferenceRepository referenceRepository) {
        this.messageRepository = messageRepository;
        this.referenceRepository = referenceRepository;
    }

    @Transactional
    public boolean tryAcquireLock(String operationId) {
        ChatNativeVideoMessage row = messageRepository.findByIdForUpdate(operationId).orElse(null);
        if (row == null) {
            return false;
        }
        if (row.getStatus() == ChatNativeVideoStatus.sent
            || row.getStatus() == ChatNativeVideoStatus.failed) {
            return false;
        }
        Instant now = Instant.now();
        if (row.getLockUntil() != null && row.getLockUntil().isAfter(now)) {
            return false;
        }
        row.setLockUntil(now.plus(LOCK_TTL));
        row.setUpdatedAt(now);
        messageRepository.save(row);
        return true;
    }

    @Transactional
    public void markSent(String operationId, String msgKey, Long msgSeq) {
        ChatNativeVideoMessage row = messageRepository.findByIdForUpdate(operationId).orElse(null);
        if (row == null || row.getStatus() == ChatNativeVideoStatus.sent) {
            return;
        }
        Instant now = Instant.now();
        row.setStatus(ChatNativeVideoStatus.sent);
        row.setMsgKey(msgKey);
        row.setMsgSeq(msgSeq);
        row.setFailCode(null);
        row.setLockUntil(null);
        row.setSentAt(now);
        row.setUpdatedAt(now);
        messageRepository.save(row);
        ChatAttachmentReference ref = referenceRepository.findByReferenceId(row.getReferenceId()).orElse(null);
        if (ref != null && ref.getState() != ChatReferenceState.confirmed) {
            ref.setState(ChatReferenceState.confirmed);
            ref.setConfirmedAt(now);
            ref.setExpiresAt(null);
            ref.setProviderMessageId(msgKey != null ? msgKey : (msgSeq == null ? null : String.valueOf(msgSeq)));
            referenceRepository.save(ref);
        }
    }

    @Transactional
    public void mark(String operationId, ChatNativeVideoStatus status, String failCode,
                     String msgKey, Long msgSeq) {
        ChatNativeVideoMessage row = messageRepository.findByIdForUpdate(operationId).orElse(null);
        if (row == null || row.getStatus() == ChatNativeVideoStatus.sent) {
            return;
        }
        row.setStatus(status);
        row.setFailCode(failCode);
        if (msgKey != null) {
            row.setMsgKey(msgKey);
        }
        if (msgSeq != null) {
            row.setMsgSeq(msgSeq);
        }
        row.setLockUntil(null);
        row.setUpdatedAt(Instant.now());
        messageRepository.save(row);
    }
}
