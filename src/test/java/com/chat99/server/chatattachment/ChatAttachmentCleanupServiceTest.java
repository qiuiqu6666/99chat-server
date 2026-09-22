package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatAttachmentCleanupServiceTest {

    @Mock ChatUploadService uploadService;
    @Mock ChatUploadSessionRepository sessionRepository;
    @Mock ChatAttachmentRepository attachmentRepository;
    @Mock ChatAttachmentReferenceRepository referenceRepository;
    @Mock ChatAttachmentOssClient oss;
    @Mock ChatAttachmentQuotaService quotaService;

    @Test
    void unreferencedReadyGetsPurgeAfter() {
        ChatAttachmentCleanupService service = new ChatAttachmentCleanupService(
            ChatAttachmentTestSupport.props(), uploadService, sessionRepository,
            attachmentRepository, referenceRepository, oss, quotaService);
        ChatAttachment ready = new ChatAttachment();
        ready.setAttachmentId("att_1");
        ready.setStatus(ChatAttachmentStatus.ready);
        ready.setReadyAt(Instant.now().minusSeconds(300_000));
        when(attachmentRepository.findByStatusAndReadyAtBefore(
            org.mockito.ArgumentMatchers.eq(ChatAttachmentStatus.ready),
            org.mockito.ArgumentMatchers.any())).thenReturn(List.of(ready));
        when(referenceRepository.existsByAttachmentIdAndStateIn(
            org.mockito.ArgumentMatchers.eq("att_1"),
            org.mockito.ArgumentMatchers.any())).thenReturn(false);

        service.markUnreferenced();
        assertThat(ready.getPurgeAfter()).isNotNull();
    }

    @Test
    void protectingReferenceSkipsPurge() {
        ChatAttachmentCleanupService service = new ChatAttachmentCleanupService(
            ChatAttachmentTestSupport.props(), uploadService, sessionRepository,
            attachmentRepository, referenceRepository, oss, quotaService);
        ChatAttachment ready = new ChatAttachment();
        ready.setAttachmentId("att_2");
        ready.setStatus(ChatAttachmentStatus.ready);
        ready.setReadyAt(Instant.now().minusSeconds(300_000));
        when(attachmentRepository.findByStatusAndReadyAtBefore(
            org.mockito.ArgumentMatchers.eq(ChatAttachmentStatus.ready),
            org.mockito.ArgumentMatchers.any())).thenReturn(List.of(ready));
        when(referenceRepository.existsByAttachmentIdAndStateIn(
            org.mockito.ArgumentMatchers.eq("att_2"),
            org.mockito.ArgumentMatchers.any())).thenReturn(true);

        service.markUnreferenced();
        assertThat(ready.getPurgeAfter()).isNull();
    }

    @Test
    void retentionExpiredDeletesEvenIfConfirmed() {
        ChatAttachmentCleanupService service = new ChatAttachmentCleanupService(
            ChatAttachmentTestSupport.props(), uploadService, sessionRepository,
            attachmentRepository, referenceRepository, oss, quotaService);
        ChatAttachment expired = new ChatAttachment();
        expired.setAttachmentId("att_exp");
        expired.setOwnerUserId("u1");
        expired.setObjectKey("chat-attachments/v1/originals/x");
        expired.setStatus(ChatAttachmentStatus.ready);
        expired.setSizeBytes(100L);
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        when(attachmentRepository.findExpiredByStatuses(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(List.of(expired));
        when(attachmentRepository.findByStatusAndPurgeAfterBefore(
            org.mockito.ArgumentMatchers.eq(ChatAttachmentStatus.ready),
            org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(attachmentRepository.findByStatus(ChatAttachmentStatus.deleting)).thenReturn(List.of());
        when(attachmentRepository.casStatus(
            org.mockito.ArgumentMatchers.eq("att_exp"),
            org.mockito.ArgumentMatchers.eq(ChatAttachmentStatus.ready),
            org.mockito.ArgumentMatchers.eq(ChatAttachmentStatus.deleting))).thenReturn(1);
        when(attachmentRepository.findByParentAttachmentId("att_exp")).thenReturn(List.of());

        service.deleteDue();

        org.mockito.Mockito.verify(oss).deleteObject("chat-attachments/v1/originals/x");
        org.mockito.Mockito.verify(quotaService).releaseUsed("u1", 100L);
        assertThat(expired.getStatus()).isEqualTo(ChatAttachmentStatus.deleted);
        org.mockito.Mockito.verify(referenceRepository, org.mockito.Mockito.never())
            .existsByAttachmentIdAndStateIn(org.mockito.ArgumentMatchers.eq("att_exp"),
                org.mockito.ArgumentMatchers.any());
    }
}
