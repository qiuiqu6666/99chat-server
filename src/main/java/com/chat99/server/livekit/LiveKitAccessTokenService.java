package com.chat99.server.livekit;

import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signs LiveKit Access Tokens (HS256) using API key/secret.
 * Claim shape matches LiveKit server SDK grants.
 */
@Service
public class LiveKitAccessTokenService {

    private final LiveKitProperties props;

    public LiveKitAccessTokenService(LiveKitProperties props) {
        this.props = props;
    }

    public IssuedToken issueRoomToken(String identity, String roomName) {
        requireConfigured();
        if (identity == null || identity.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_IDENTITY");
        }
        if (roomName == null || roomName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ROOM");
        }
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.tokenTtlSeconds());
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("roomJoin", true);
        video.put("room", roomName);
        video.put("canPublish", true);
        video.put("canSubscribe", true);
        video.put("canPublishData", true);

        SecretKey key = hmacKey(props.apiSecret());
        String jwt = Jwts.builder()
            .issuer(props.apiKey())
            .subject(identity)
            .notBefore(Date.from(now.minusSeconds(10)))
            .expiration(Date.from(exp))
            .claim("video", video)
            .claim("name", identity)
            .signWith(key, Jwts.SIG.HS256)
            .compact();
        return new IssuedToken(jwt, exp);
    }

    public void requireConfigured() {
        if (!props.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LIVEKIT_NOT_CONFIGURED");
        }
    }

    static SecretKey hmacKey(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public record IssuedToken(String token, Instant expiresAt) {}
}
