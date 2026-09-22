package com.chat99.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApnsPushSenderTest {

    @Test
    void sanitizeCollapseIdReturnsNullForBlank() {
        assertThat(ApnsPushSender.sanitizeCollapseId(null)).isNull();
        assertThat(ApnsPushSender.sanitizeCollapseId("  ")).isNull();
    }

    @Test
    void sanitizeCollapseIdTruncatesLongKeys() {
        String longKey = "k".repeat(80);
        assertThat(ApnsPushSender.sanitizeCollapseId(longKey)).hasSize(64);
    }

    @Test
    void sanitizeCollapseIdKeepsShortMsgKey() {
        String msgKey = "1224486638_2071424560_1780039003";
        assertThat(ApnsPushSender.sanitizeCollapseId(msgKey)).isEqualTo(msgKey);
    }

    @Test
    void buildPayloadJson_setsMutableContentAndRootAvatarUrl() throws Exception {
        PushMessage message = PushMessage.of("Alice", "hi")
            .withData("type", "im_chat")
            .withData("avatarUrl", "https://cdn.example.com/a.jpg");

        String payload = ApnsPushSender.buildPayloadJson(message);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(payload, Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> aps = (Map<String, Object>) json.get("aps");
        assertThat(aps.get("mutable-content")).isEqualTo(1);
        assertThat(json.get("avatarUrl")).isEqualTo("https://cdn.example.com/a.jpg");
        assertThat(json.get("type")).isEqualTo("im_chat");
    }

    @Test
    void buildPayloadJson_omitsMutableContentWithoutAvatar() throws Exception {
        PushMessage message = PushMessage.of("Alice", "hi").withData("type", "im_chat");
        String payload = ApnsPushSender.buildPayloadJson(message);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(payload, Map.class);
        assertThat(json).doesNotContainKey("avatarUrl");
        @SuppressWarnings("unchecked")
        Map<String, Object> aps = (Map<String, Object>) json.get("aps");
        assertThat(aps).doesNotContainKey("mutable-content");
    }

    @Test
    void buildPayloadJson_matchesFrontendChatContract() throws Exception {
        PushMessage message = PushMessage.of("Alice", "hello")
            .withData("type", "im_chat")
            .withData("chatType", "c2c")
            .withData("fromAccount", "user-1")
            .withData("msgKey", "msg-1")
            .withData("avatarUrl", "https://cdn.example.com/avatar/user-1.jpg")
            .withApnsGrouping("c2c_user-1", "c2c_user-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> root = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(ApnsPushSender.buildPayloadJson(message), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> aps = (Map<String, Object>) root.get("aps");

        assertThat(aps.get("mutable-content")).isEqualTo(1);
        assertThat(aps.get("thread-id")).isEqualTo("c2c_user-1");
        assertThat(root)
            .containsEntry("type", "im_chat")
            .containsEntry("chatType", "c2c")
            .containsEntry("fromAccount", "user-1")
            .containsEntry("msgKey", "msg-1")
            .containsEntry("avatarUrl", "https://cdn.example.com/avatar/user-1.jpg")
            .doesNotContainKey("data");
    }
}
