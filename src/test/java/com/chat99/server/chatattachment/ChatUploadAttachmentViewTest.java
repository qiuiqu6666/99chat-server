package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.chat99.server.oss.ImageProcessor;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatUploadAttachmentViewTest {

    @Test
    void attachmentViewIncludesMediaProbe() {
        ChatUploadService service = new ChatUploadService(
            ChatAttachmentTestSupport.props(),
            mock(ChatAttachmentOssClient.class),
            mock(ChatAttachmentQuotaService.class),
            mock(ChatAttachmentAuthService.class),
            mock(ChatAttachmentRepository.class),
            mock(ChatUploadSessionRepository.class),
            mock(ChatUploadPartRepository.class),
            mock(ImageProcessor.class),
            mock(ChatAttachmentThumbnailExtractService.class),
            mock(ChatMediaProbeService.class));
        ChatAttachment attachment = new ChatAttachment();
        attachment.setAttachmentId("att_1");
        attachment.setKind(ChatAttachmentKind.video);
        attachment.setNativeMessageKind(ChatNativeMessageKind.video);
        attachment.setStatus(ChatAttachmentStatus.ready);
        attachment.setDeclaredSizeBytes(1);
        attachment.setSizeBytes(1L);
        attachment.setChecksumStatus(ChatChecksumStatus.verified);
        attachment.setDurationMs(120_000L);
        attachment.setWidth(1920);
        attachment.setHeight(1080);
        attachment.setMediaProbeSource(ChatMetadataProvenance.server);
        attachment.setMediaProbedAt(Instant.parse("2026-09-18T09:00:00Z"));

        Map<String, Object> view = service.attachmentView(attachment);
        assertThat(view.get("durationMs")).isEqualTo(120_000L);
        assertThat(view.get("width")).isEqualTo(1920);
        assertThat(view.get("height")).isEqualTo(1080);
        @SuppressWarnings("unchecked")
        Map<String, Object> probe = (Map<String, Object>) view.get("mediaProbe");
        assertThat(probe.get("source")).isEqualTo("server");
        assertThat(probe.get("probedAt")).isEqualTo("2026-09-18T09:00:00Z");
    }
}
