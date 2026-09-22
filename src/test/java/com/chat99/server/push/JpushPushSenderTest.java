package com.chat99.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JpushPushSenderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JpushThirdPartyConfig ENABLED_THIRD_PARTY = new JpushThirdPartyConfig(
        true,
        "secondary_push",
        "secondary_push",
        "secondary_push",
        "secondary_push",
        "secondary_push",
        "mi-channel-001");

    @Test
    void buildPushBodyIncludesThirdPartyChannel() throws Exception {
        PushMessage message = PushMessage.of("推送验证", "正文");
        Map<String, Object> body = JpushPushSender.buildPushBody(
            List.of("reg-1"), message, ENABLED_THIRD_PARTY);

        assertThat(body).containsKey("options");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        @SuppressWarnings("unchecked")
        Map<String, Object> channels = (Map<String, Object>) options.get("third_party_channel");

        assertThat(channels).containsKeys("huawei", "honor", "oppo", "vivo", "xiaomi");
        @SuppressWarnings("unchecked")
        Map<String, Object> huawei = (Map<String, Object>) channels.get("huawei");
        assertThat(huawei.get("distribution")).isEqualTo("secondary_push");
        @SuppressWarnings("unchecked")
        Map<String, Object> xiaomi = (Map<String, Object>) channels.get("xiaomi");
        assertThat(xiaomi.get("channel_id")).isEqualTo("mi-channel-001");
    }

    @Test
    void buildPushBodyOmitsThirdPartyWhenDisabled() {
        Map<String, Object> body = JpushPushSender.buildPushBody(
            List.of("reg-1"),
            PushMessage.of("title", "body"),
            JpushThirdPartyConfig.disabled());

        assertThat(body).doesNotContainKey("options");
    }

    @Test
    void buildPushBodyOmitsXiaomiWhenChannelIdBlank() {
        JpushThirdPartyConfig config = new JpushThirdPartyConfig(
            true, "secondary_push", "secondary_push", "secondary_push", "secondary_push",
            "secondary_push", "");
        Map<String, Object> body = JpushPushSender.buildPushBody(
            List.of("reg-1"), PushMessage.of("title", "body"), config);

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        @SuppressWarnings("unchecked")
        Map<String, Object> channels = (Map<String, Object>) options.get("third_party_channel");
        assertThat(channels).doesNotContainKey("xiaomi");
    }

    @Test
    void buildPushBodyDoesNotIncludeAndroidGroupField() throws Exception {
        PushMessage message = PushMessage.of("推送验证", "正文")
            .withApnsGrouping("c2c_peer", "c2c_peer");
        Map<String, Object> body = JpushPushSender.buildPushBody(
            List.of("reg-1"), message, ENABLED_THIRD_PARTY);

        String payload = JSON.writeValueAsString(body);
        assertThat(payload).doesNotContain("\"group\"");
        assertThat(payload).contains("threadId");
        assertThat(payload).contains("推送验证");
    }

    @Test
    void buildAndroidNotificationPutsThreadIdInExtras() {
        PushMessage message = PushMessage.of("标题", "内容")
            .withApnsGrouping("c2c_peer", "c2c_peer")
            .withData("type", "im_chat");

        Map<String, Object> android = JpushPushSender.buildAndroidNotification(message);
        @SuppressWarnings("unchecked")
        Map<String, Object> extras = (Map<String, Object>) android.get("extras");

        assertThat(extras.get("threadId")).isEqualTo("c2c_peer");
    }

    @Test
    void buildAndroidNotificationPutsAvatarInExtrasAndLargeIcon() {
        PushMessage message = PushMessage.of("标题", "内容")
            .withApnsGrouping("c2c_peer", "c2c_peer")
            .withData("type", "im_chat")
            .withData("avatarUrl", "https://cdn.example.com/a.jpg");

        Map<String, Object> android = JpushPushSender.buildAndroidNotification(message);
        @SuppressWarnings("unchecked")
        Map<String, Object> extras = (Map<String, Object>) android.get("extras");

        assertThat(extras.get("avatarUrl")).isEqualTo("https://cdn.example.com/a.jpg");
        assertThat(android.get("large_icon")).isEqualTo("https://cdn.example.com/a.jpg");
        assertThat(android).doesNotContainKey("group");
    }
}
