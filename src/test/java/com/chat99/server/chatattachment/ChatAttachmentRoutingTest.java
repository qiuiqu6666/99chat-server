package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ChatAttachmentRoutingTest {

    private final ChatAttachmentProperties props = ChatAttachmentTestSupport.props();

    @Test
    void imageAndSoundRouteAfter28MiB() {
        long at = 29_360_128L;
        long over = 29_360_129L;
        assertThat(ChatAttachmentRouting.requiresSelfHosted(at, ChatNativeMessageKind.image, props)).isFalse();
        assertThat(ChatAttachmentRouting.requiresSelfHosted(over, ChatNativeMessageKind.image, props)).isTrue();
        assertThat(ChatAttachmentRouting.requiresSelfHosted(at, ChatNativeMessageKind.sound, props)).isFalse();
        assertThat(ChatAttachmentRouting.requiresSelfHosted(over, ChatNativeMessageKind.sound, props)).isTrue();
    }

    @Test
    void videoAndFileRouteAfter100MiB() {
        long at = 104_857_600L;
        long over = 104_857_601L;
        long under = 104_857_599L;
        assertThat(ChatAttachmentRouting.requiresSelfHosted(under, ChatNativeMessageKind.video, props)).isFalse();
        assertThat(ChatAttachmentRouting.requiresSelfHosted(at, ChatNativeMessageKind.video, props)).isFalse();
        assertThat(ChatAttachmentRouting.requiresSelfHosted(over, ChatNativeMessageKind.video, props)).isTrue();
        assertThat(ChatAttachmentRouting.requiresSelfHosted(at, ChatNativeMessageKind.file, props)).isFalse();
        assertThat(ChatAttachmentRouting.requiresSelfHosted(over, ChatNativeMessageKind.file, props)).isTrue();
    }

    @Test
    void audioFileUsesFileCapNotSound() {
        long mid = 50_000_000L;
        assertThat(ChatAttachmentRouting.requiresSelfHosted(mid, ChatNativeMessageKind.sound, props)).isTrue();
        assertThat(ChatAttachmentRouting.requiresSelfHosted(mid, ChatNativeMessageKind.file, props)).isFalse();
        ChatAttachmentRouting.validateKindPair(ChatAttachmentKind.audio, ChatNativeMessageKind.file);
        ChatAttachmentRouting.validateKindPair(ChatAttachmentKind.audio, ChatNativeMessageKind.sound);
        assertThatThrownBy(() ->
            ChatAttachmentRouting.validateKindPair(ChatAttachmentKind.image, ChatNativeMessageKind.file))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void twoGiBFits256Parts() {
        assertThat(ChatAttachmentRouting.expectedPartCount(2_147_483_648L, 8_388_608L)).isEqualTo(256);
    }
}
