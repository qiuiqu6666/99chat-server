package com.chat99.server.livekit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LiveKitAccessTokenServiceTest {

    @Test
    void issueRoomTokenContainsVideoGrant() {
        LiveKitProperties props = new LiveKitProperties(
            true, "wss://livekit.example.com", "devkey", "secret-value-at-least-32-bytes!!",
            3600, 60, true);
        LiveKitAccessTokenService service = new LiveKitAccessTokenService(props);

        LiveKitAccessTokenService.IssuedToken issued = service.issueRoomToken("user1", "call_abc");
        assertNotNull(issued.token());
        assertTrue(issued.expiresAt().isAfter(java.time.Instant.now()));

        Claims claims = Jwts.parser()
            .verifyWith(LiveKitAccessTokenService.hmacKey(props.apiSecret()))
            .requireIssuer("devkey")
            .build()
            .parseSignedClaims(issued.token())
            .getPayload();
        assertEquals("user1", claims.getSubject());
        @SuppressWarnings("unchecked")
        Map<String, Object> video = claims.get("video", Map.class);
        assertEquals(true, video.get("roomJoin"));
        assertEquals("call_abc", video.get("room"));
        assertEquals(true, video.get("canPublish"));
        assertEquals(true, video.get("canSubscribe"));
    }
}
