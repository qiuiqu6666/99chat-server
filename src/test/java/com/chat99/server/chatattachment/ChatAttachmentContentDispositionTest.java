package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatAttachmentContentDispositionTest {

    @Test
    void utf8FilenameUsesRfc5987() {
        String header = ChatAttachmentContentDisposition.attachment("旅行.mp4");
        assertThat(header).contains("filename=\".mp4\"");
        assertThat(header).contains("filename*=UTF-8''");
        assertThat(header).contains("%E6%97%85%E8%A1%8C.mp4");
        assertThat(header).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void jpegMagicMatches() {
        assertThat(ChatAttachmentContentDisposition.isJpeg(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
            .isTrue();
        assertThat(ChatAttachmentContentDisposition.isJpeg(new byte[] {0x00, 0x00, 0x00})).isFalse();
    }

    @Test
    void confirmedRetentionDaysIsThirty() {
        assertThat(ChatAttachmentTestSupport.props().confirmedRetentionDays()).isEqualTo(30);
    }

    @Test
    void nativeVideoMessageEnabledDefaultsOff() {
        assertThat(ChatAttachmentTestSupport.props().nativeVideoMessageEnabled()).isFalse();
    }
}
