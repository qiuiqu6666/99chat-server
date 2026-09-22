/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.security;

import com.chat99.server.common.AppSettingService;
import com.chat99.server.security.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expireSeconds;

    public JwtService(AppSettingService settings, JwtProperties props) {
        String secret = settings.get("JWT_SECRET").filter(value -> !value.isBlank()).orElse(props.secret());
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Required app_setting missing: JWT_SECRET");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("app_setting JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor((byte[])secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = props.expireSeconds();
    }

    public String issue(String userId) {
        return this.issue(userId, "", null);
    }

    public String issue(String userId, String deviceId, String jti) {
        Date now = new Date();
        JwtBuilder builder = Jwts.builder().subject(userId).issuedAt(now).expiration(new Date(now.getTime() + this.expireSeconds * 1000L));
        if (jti != null && !jti.isBlank()) {
            builder.id(jti);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            builder.claim("did", (Object)deviceId);
        }
        return builder.signWith((Key)this.key).compact();
    }

    public String parseUserId(String token) {
        return this.parseClaims(token).getSubject();
    }

    public Optional<String> parseJti(String token) {
        try {
            String jti = this.parseClaims(token).getId();
            if (jti == null || jti.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(jti);
        }
        catch (JwtException e) {
            return Optional.empty();
        }
    }

    public Optional<String> parseDeviceId(String token) {
        try {
            Object did = this.parseClaims(token).get("did");
            if (did == null) {
                return Optional.empty();
            }
            String value = did.toString().trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        catch (JwtException e) {
            return Optional.empty();
        }
    }

    private Claims parseClaims(String token) {
        return (Claims)Jwts.parser().verifyWith(this.key).build().parseSignedClaims((CharSequence)token).getPayload();
    }

    public long expireSeconds() {
        return this.expireSeconds;
    }
}
