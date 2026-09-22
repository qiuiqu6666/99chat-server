package com.chat99.server.adminapi;

import com.chat99.server.common.AppSettingService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class AdminJwtService {

    public static final String AUDIENCE = "admin-api";

    private final SecretKey key;
    private final long expireSeconds;

    public AdminJwtService(AppSettingService settings, AdminApiProperties props) {
        String secret = settings.get("JWT_SECRET").filter(value -> !value.isBlank()).orElse(props.jwtSecret());
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Required app_setting missing: JWT_SECRET");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("app_setting JWT_SECRET must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = props.jwtExpireSeconds();
    }

    public String issue(String username, List<String> permissions, String jti) {
        Date now = new Date();
        return Jwts.builder()
            .subject(username)
            .id(jti)
            .audience().add(AUDIENCE).and()
            .claim("permissions", String.join(",", permissions))
            .issuedAt(now)
            .expiration(new Date(now.getTime() + expireSeconds * 1000L))
            .signWith(key)
            .compact();
    }

    public AdminTokenClaims parse(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(key)
            .requireAudience(AUDIENCE)
            .build()
            .parseSignedClaims(token)
            .getPayload();
        String perms = claims.get("permissions", String.class);
        List<String> permissions = perms == null || perms.isBlank()
            ? List.of()
            : Arrays.stream(perms.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new AdminTokenClaims(claims.getSubject(), claims.getId(), permissions);
    }

    public long expireSeconds() {
        return expireSeconds;
    }

    public record AdminTokenClaims(String username, String jti, List<String> permissions) {}
}
