package com.chat99.server.livekit;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies LiveKit webhook Authorization JWT (sha256 of raw body).
 */
@Component
public class LiveKitWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookVerifier.class);

    private final LiveKitProperties props;

    public LiveKitWebhookVerifier(LiveKitProperties props) {
        this.props = props;
    }

    public void verify(String authorizationHeader, byte[] body) {
        if (!props.webhookEnabled()) {
            return;
        }
        if (props.apiSecret() == null || props.apiSecret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LIVEKIT_NOT_CONFIGURED");
        }
        String token = extractBearer(authorizationHeader);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "LIVEKIT_WEBHOOK_UNAUTHORIZED");
        }
        try {
            SecretKey key = LiveKitAccessTokenService.hmacKey(props.apiSecret());
            Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(props.apiKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
            String claimed = claims.get("sha256", String.class);
            if (claimed == null || claimed.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "LIVEKIT_WEBHOOK_UNAUTHORIZED");
            }
            String actual = sha256Base64(body == null ? new byte[0] : body);
            if (!MessageDigest.isEqual(
                claimed.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
                log.warn("livekit webhook sha256 mismatch");
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "LIVEKIT_WEBHOOK_UNAUTHORIZED");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("livekit webhook jwt verify failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "LIVEKIT_WEBHOOK_UNAUTHORIZED");
        }
    }

    static String extractBearer(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String h = authorizationHeader.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return h.substring(7).trim();
        }
        return h;
    }

    static String sha256Base64(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body);
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("sha256 unavailable", e);
        }
    }
}
