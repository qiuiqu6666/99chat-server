package com.chat99.server.chatattachment;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatAttachmentCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentCleanupService.class);
    private static final Set<ChatReferenceState> PROTECTING = EnumSet.of(
        ChatReferenceState.reserved, ChatReferenceState.confirmed, ChatReferenceState.manualRequired);
    private static final Set<ChatUploadStatus> OPEN = EnumSet.of(
        ChatUploadStatus.initiated, ChatUploadStatus.uploading);

    private final ChatAttachmentProperties props;
    private final ChatUploadService uploadService;
    private final ChatUploadSessionRepository sessionRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatAttachmentReferenceRepository referenceRepository;
    private final ChatAttachmentOssClient oss;
    private final ChatAttachmentQuotaService quotaService;

    public ChatAttachmentCleanupService(ChatAttachmentProperties props,
                                        ChatUploadService uploadService,
                                        ChatUploadSessionRepository sessionRepository,
                                        ChatAttachmentRepository attachmentRepository,
                                        ChatAttachmentReferenceRepository referenceRepository,
                                        ChatAttachmentOssClient oss,
                                        ChatAttachmentQuotaService quotaService) {
        this.props = props;
        this.uploadService = uploadService;
        this.sessionRepository = sessionRepository;
        this.attachmentRepository = attachmentRepository;
        this.referenceRepository = referenceRepository;
        this.oss = oss;
        this.quotaService = quotaService;
    }

    @Transactional
    public void expireUploads() {
        Instant now = Instant.now();
        List<ChatUploadSession> expired = sessionRepository.findByStatusInAndExpiresAtBefore(OPEN, now);
        for (ChatUploadSession session : expired) {
            try {
                uploadService.expire(session);
            } catch (Exception e) {
                log.warn("expire upload failed uploadId={} err={}", session.getUploadId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void markUnreferenced() {
        Instant cutoff = Instant.now().minusSeconds(props.unreferencedReadyTtlSeconds());
        List<ChatAttachment> ready = attachmentRepository
            .findByStatusAndReadyAtBefore(ChatAttachmentStatus.ready, cutoff);
        Instant purgeAt = Instant.now().plusSeconds(props.deleteGraceSeconds());
        for (ChatAttachment attachment : ready) {
            if (attachment.getParentAttachmentId() != null) {
                continue;
            }
            if (referenceRepository.existsByAttachmentIdAndStateIn(attachment.getAttachmentId(), PROTECTING)) {
                continue;
            }
            if (attachment.getPurgeAfter() == null) {
                attachment.setPurgeAfter(purgeAt);
                attachmentRepository.save(attachment);
            }
        }
    }

    @Transactional
    public void deleteDue() {
        Instant now = Instant.now();
        List<ChatAttachment> expired = attachmentRepository.findExpiredByStatuses(
            EnumSet.of(ChatAttachmentStatus.ready, ChatAttachmentStatus.rejected), now);
        for (ChatAttachment attachment : expired) {
            deleteAttachment(attachment, "retention_expired");
        }
        List<ChatAttachment> purged = attachmentRepository
            .findByStatusAndPurgeAfterBefore(ChatAttachmentStatus.ready, now);
        for (ChatAttachment attachment : purged) {
            if (referenceRepository.existsByAttachmentIdAndStateIn(attachment.getAttachmentId(), PROTECTING)) {
                attachment.setPurgeAfter(null);
                attachmentRepository.save(attachment);
                continue;
            }
            deleteAttachment(attachment, "unreferenced");
        }
        List<ChatAttachment> deleting = attachmentRepository.findByStatus(ChatAttachmentStatus.deleting);
        for (ChatAttachment attachment : deleting) {
            deleteAttachment(attachment, "retry");
        }
    }

    @Transactional
    public void reconcileReserved() {
        Instant cutoff = Instant.now().minusSeconds(1);
        List<ChatAttachmentReference> stale =
            referenceRepository.findByStateAndExpiresAtBefore(ChatReferenceState.reserved, cutoff);
        for (ChatAttachmentReference ref : stale) {
            ref.setState(ChatReferenceState.manualRequired);
            referenceRepository.save(ref);
            log.warn("manualRequired referenceId={} attachmentId={} clientOperationId={}",
                ref.getReferenceId(), ref.getAttachmentId(), ref.getClientOperationId());
        }
    }

    private void deleteAttachment(ChatAttachment attachment, String reason) {
        if (attachment.getStatus() == ChatAttachmentStatus.deleted) {
            return;
        }
        int cas = attachmentRepository.casStatus(
            attachment.getAttachmentId(), attachment.getStatus(), ChatAttachmentStatus.deleting);
        if (cas == 0 && attachment.getStatus() != ChatAttachmentStatus.deleting) {
            return;
        }
        try {
            for (ChatAttachment child : attachmentRepository.findByParentAttachmentId(attachment.getAttachmentId())) {
                oss.deleteObject(child.getObjectKey());
                child.setStatus(ChatAttachmentStatus.deleted);
                attachmentRepository.save(child);
                if (child.getSizeBytes() != null) {
                    quotaService.releaseUsed(child.getOwnerUserId(), child.getSizeBytes());
                }
            }
            if (attachment.getThumbnailAttachmentId() != null) {
                attachmentRepository.findByAttachmentId(attachment.getThumbnailAttachmentId()).ifPresent(thumb -> {
                    oss.deleteObject(thumb.getObjectKey());
                    thumb.setStatus(ChatAttachmentStatus.deleted);
                    attachmentRepository.save(thumb);
                    if (thumb.getSizeBytes() != null) {
                        quotaService.releaseUsed(thumb.getOwnerUserId(), thumb.getSizeBytes());
                    }
                });
            }
            oss.deleteObject(attachment.getObjectKey());
            if (attachment.getSizeBytes() != null && attachment.getStatus() != ChatAttachmentStatus.deleted) {
                quotaService.releaseUsed(attachment.getOwnerUserId(), attachment.getSizeBytes());
            }
            attachment.setStatus(ChatAttachmentStatus.deleted);
            attachmentRepository.save(attachment);
            log.info("chat attachment deleted attachmentId={} reason={}", attachment.getAttachmentId(), reason);
        } catch (Exception e) {
            log.warn("chat attachment delete retry attachmentId={} err={}",
                attachment.getAttachmentId(), e.getMessage());
        }
    }
}
