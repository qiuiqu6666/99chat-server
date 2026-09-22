package com.chat99.server.livekit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LiveKitWebhookVerifierTest {

    @Test
    void verifyAcceptsValidSignature() {
        String secret = "secret-value-at-least-32-bytes!!";
        LiveKitProperties props = new LiveKitProperties(
            true, "wss://x", "devkey", secret, 3600, 60, true);
        LiveKitWebhookVerifier verifier = new LiveKitWebhookVerifier(props);
        byte[] body = "{\"event\":\"room_finished\"}".getBytes(StandardCharsets.UTF_8);
        String sha = LiveKitWebhookVerifier.sha256Base64(body);
        String jwt = Jwts.builder()
            .issuer("devkey")
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .claim("sha256", sha)
            .signWith(LiveKitAccessTokenService.hmacKey(secret), Jwts.SIG.HS256)
            .compact();

        assertDoesNotThrow(() -> verifier.verify("Bearer " + jwt, body));
    }

    @Test
    void verifyRejectsBadBody() {
        String secret = "secret-value-at-least-32-bytes!!";
        LiveKitProperties props = new LiveKitProperties(
            true, "wss://x", "devkey", secret, 3600, 60, true);
        LiveKitWebhookVerifier verifier = new LiveKitWebhookVerifier(props);
        byte[] body = "{\"event\":\"room_finished\"}".getBytes(StandardCharsets.UTF_8);
        String sha = LiveKitWebhookVerifier.sha256Base64(body);
        String jwt = Jwts.builder()
            .issuer("devkey")
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .claim("sha256", sha)
            .signWith(LiveKitAccessTokenService.hmacKey(secret), Jwts.SIG.HS256)
            .compact();

        assertThrows(ResponseStatusException.class,
            () -> verifier.verify("Bearer " + jwt, "{\"event\":\"other\"}".getBytes(StandardCharsets.UTF_8)));
    }
}
