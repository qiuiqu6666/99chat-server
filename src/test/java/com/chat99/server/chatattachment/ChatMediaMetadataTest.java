package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatMediaMetadataTest {

    @Test
    void sanitizesDurationAndPixels() {
        assertThat(ChatMediaMetadata.sanitizeDurationMs(null)).isNull();
        assertThat(ChatMediaMetadata.sanitizeDurationMs(0L)).isNull();
        assertThat(ChatMediaMetadata.sanitizeDurationMs(-3L)).isNull();
        assertThat(ChatMediaMetadata.sanitizeDurationMs(24L * 3600 * 1000 + 1)).isNull();
        assertThat(ChatMediaMetadata.sanitizeDurationMs(125_000L)).isEqualTo(125_000L);
        assertThat(ChatMediaMetadata.sanitizePx(0)).isNull();
        assertThat(ChatMediaMetadata.sanitizePx(1920)).isEqualTo(1920);
    }

    @Test
    void ignoresDurationForNonAvKinds() {
        assertThat(ChatMediaMetadata.durationForKind(ChatAttachmentKind.file, 3_000L)).isNull();
        assertThat(ChatMediaMetadata.durationForKind(ChatAttachmentKind.image, 3_000L)).isNull();
        assertThat(ChatMediaMetadata.durationForKind(ChatAttachmentKind.video, 3_000L)).isEqualTo(3_000L);
        assertThat(ChatMediaMetadata.durationForKind(ChatAttachmentKind.audio, 3_000L)).isEqualTo(3_000L);
    }

    @Test
    void videoSecondCeilsAndFallsBackToZero() {
        assertThat(ChatMediaMetadata.videoSecond(null)).isZero();
        assertThat(ChatMediaMetadata.videoSecond(0L)).isZero();
        assertThat(ChatMediaMetadata.videoSecond(1L)).isEqualTo(1L);
        assertThat(ChatMediaMetadata.videoSecond(1000L)).isEqualTo(1L);
        assertThat(ChatMediaMetadata.videoSecond(1001L)).isEqualTo(2L);
        assertThat(ChatMediaMetadata.videoSecond(120_000L)).isEqualTo(120L);
    }
}
