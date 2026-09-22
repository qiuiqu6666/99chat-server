package com.chat99.server.chatattachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatNativeVideoMediaTokenTest {

    @Test
    void roundTripAndRejectsTamper() {
        Instant exp = Instant.now().plusSeconds(3600);
        String token = ChatNativeVideoMediaToken.mint("video", "att_video", exp, "secret");
        ChatNativeVideoMediaToken.Claims claims = ChatNativeVideoMediaToken.verify(token, "secret");
        assertThat(claims).isNotNull();
        assertThat(claims.purpose()).isEqualTo("video");
        assertThat(claims.attachmentId()).isEqualTo("att_video");
        assertThat(ChatNativeVideoMediaToken.verify(token, "other")).isNull();
        assertThat(ChatNativeVideoMediaToken.verify(token.substring(1), "secret")).isNull();
    }

    @Test
    void parseRangeInclusive() {
        assertThat(ChatNativeVideoMediaService.parseRange("bytes=0-9", 100))
            .containsExactly(0L, 9L);
        assertThat(ChatNativeVideoMediaService.parseRange("bytes=50-", 100))
            .containsExactly(50L, 99L);
        assertThat(ChatNativeVideoMediaService.parseRange("bytes=-10", 100))
            .containsExactly(90L, 99L);
        assertThat(ChatNativeVideoMediaService.parseRange("bytes=100-101", 100)).isNull();
        long[] probe = ChatNativeVideoMediaService.parseRange("bytes=0-1", 127_594_253L);
        assertThat(probe).containsExactly(0L, 1L);
        assertThat(probe[1] - probe[0] + 1).isEqualTo(2L);
    }

    @Test
    void originFollowsForwardedHostNotHardcodedDomain() {
        org.springframework.mock.web.MockHttpServletRequest request =
            new org.springframework.mock.web.MockHttpServletRequest("POST", "/me/chat/native-video-messages");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "apiios.99chat.vip");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8081);
        assertThat(ChatNativeVideoMediaService.originFrom(request)).isEqualTo("https://apiios.99chat.vip");
    }

    @Test
    void originStripsDefaultHttpsPortFromHost() {
        org.springframework.mock.web.MockHttpServletRequest request =
            new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("Host", "apiios.99chat.vip:443");
        assertThat(ChatNativeVideoMediaService.originFrom(request)).isEqualTo("https://apiios.99chat.vip");
    }

    @Test
    void originKeepsHttpAndDoesNotPinDomain() {
        org.springframework.mock.web.MockHttpServletRequest forwarded =
            new org.springframework.mock.web.MockHttpServletRequest();
        forwarded.addHeader("X-Forwarded-Proto", "http");
        forwarded.addHeader("X-Forwarded-Host", "api99chat.99chat.vip");
        forwarded.setScheme("https");
        forwarded.setServerName("127.0.0.1");
        forwarded.setServerPort(8081);
        assertThat(ChatNativeVideoMediaService.originFrom(forwarded))
            .isEqualTo("http://api99chat.99chat.vip");

        org.springframework.mock.web.MockHttpServletRequest direct =
            new org.springframework.mock.web.MockHttpServletRequest();
        direct.setScheme("http");
        direct.setServerName("119.28.179.146");
        direct.setServerPort(8081);
        direct.addHeader("Host", "119.28.179.146:8081");
        assertThat(ChatNativeVideoMediaService.originFrom(direct))
            .isEqualTo("http://119.28.179.146:8081");
    }

    @Test
    void objectCdnUrlUsesImageHostAndObjectKey() {
        assertThat(ChatNativeVideoMediaService.objectCdnUrl(
            "chat-attachments/v1/originals/2026/09/q14gkm5swv/att_video",
            "https://image.99chat.vip"))
            .isEqualTo("https://image.99chat.vip/chat-attachments/v1/originals/2026/09/q14gkm5swv/att_video");
        assertThat(ChatNativeVideoMediaService.objectCdnUrl("thumbs/a#b.jpg", null))
            .isEqualTo("https://image.99chat.vip/thumbs/a%23b.jpg");
    }
}
