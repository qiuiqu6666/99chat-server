package com.chat99.server.im;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImChatPushAvatarSupportTest {

    private ImChatPushAvatarSupport support;

    @BeforeEach
    void setup() {
        support = new ImChatPushAvatarSupport(JsonMapper.builder().build());
    }

    @Test
    void extractFromCloudCustomData() {
        String url = "https://cdn.example.com/a.jpg";
        Map<String, Object> body = Map.of(
            "CloudCustomData", "{\"avatarUrl\":\"" + url + "\"}");
        assertThat(support.extractAvatarUrl(body, List.of())).isEqualTo(url);
    }

    @Test
    void extractFromCustomElemExt() {
        String url = "https://cdn.example.com/b.jpg";
        List<?> msgBody = List.of(Map.of(
            "MsgType", "TIMCustomElem",
            "MsgContent", Map.of("Ext", "{\"avatarUrl\":\"" + url + "\"}")));
        assertThat(support.extractAvatarUrl(Map.of(), msgBody)).isEqualTo(url);
    }

    @Test
    void extractPrefersExtOverFallback() {
        String extUrl = "https://cdn.example.com/ext.jpg";
        String fallback = "https://cdn.example.com/fallback.jpg";
        Map<String, Object> body = Map.of(
            "CloudCustomData", "{\"avatarUrl\":\"" + extUrl + "\"}");
        assertThat(support.resolveAvatarUrl(body, List.of(), fallback)).isEqualTo(extUrl);
    }

    @Test
    void extractUsesFallbackWhenExtMissing() {
        assertThat(support.resolveAvatarUrl(Map.of(), List.of(), "https://fallback.jpg"))
            .isEqualTo("https://fallback.jpg");
    }

    @Test
    void resolveRejectsNonPublicExtAndUsesPublicFallback() {
        Map<String, Object> body = Map.of(
            "CloudCustomData", "{\"avatarUrl\":\"avatar/private.jpg\"}");
        assertThat(support.resolveAvatarUrl(body, List.of(), "https://fallback.jpg"))
            .isEqualTo("https://fallback.jpg");
    }
}
