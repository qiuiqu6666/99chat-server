package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ChatAttachmentConversationIdsTest {

    @Test
    void sortsByUnsignedUtf8AndUsesLengthPrefix() {
        var a = ChatAttachmentConversationIds.c2c("abc", "abd");
        var b = ChatAttachmentConversationIds.c2c("abd", "abc");
        assertThat(a.conversationKey()).isEqualTo(b.conversationKey());
        assertThat(a.participantLow()).isEqualTo("abc");
        assertThat(a.participantHigh()).isEqualTo("abd");
        assertThat(a.conversationKey()).startsWith("c2c:v1:");
    }

    @Test
    void keepsCaseAndLeadingZeros() {
        var id = ChatAttachmentConversationIds.c2c("00A", "00a");
        assertThat(id.participantLow()).isEqualTo("00A");
        assertThat(id.participantHigh()).isEqualTo("00a");
        assertThat(id.conversationKey()).isNotEqualTo(
            ChatAttachmentConversationIds.c2c("00A_00a", "x").conversationKey());
    }

    @Test
    void lengthPrefixAvoidsUnderscoreAmbiguity() {
        byte[] naive = "a_bb".getBytes(StandardCharsets.UTF_8);
        byte[] canonical = ChatAttachmentConversationIds.concatLengthPrefixed(
            "a".getBytes(StandardCharsets.UTF_8), "bb".getBytes(StandardCharsets.UTF_8));
        assertThat(canonical).isNotEqualTo(naive);
        var one = ChatAttachmentConversationIds.c2c("a_b", "c");
        var two = ChatAttachmentConversationIds.c2c("a", "b_c");
        assertThat(one.conversationKey()).isNotEqualTo(two.conversationKey());
    }

    @Test
    void groupKeepsRawId() {
        var g = ChatAttachmentConversationIds.group("@TGS#abc");
        assertThat(g.conversationKey()).isEqualTo("group:v1:@TGS#abc");
        assertThat(g.groupId()).isEqualTo("@TGS#abc");
    }

    @Test
    void rejectsBlankPeer() {
        assertThatThrownBy(() -> ChatAttachmentConversationIds.c2c("u1", " "))
            .isInstanceOf(ResponseStatusException.class);
    }
}
