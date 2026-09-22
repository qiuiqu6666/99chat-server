package com.chat99.sangong.security;

import com.chat99.sangong.config.SangongProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import java.util.Date;

/** 玩家鉴权直接复用主服务 JWT（共享 CHAT99_JWT_SECRET 本地验签），不再自签发 Token。 */
@Service
public class JwtService {
    private final SangongProperties props;
    public JwtService(SangongProperties props) { this.props = props; }

    public Claims parse(String token) {
        String secret = props.getChat99JwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("未配置 CHAT99_JWT_SECRET");
        }
        return Jwts.parser().verifyWith(key(secret)).build().parseSignedClaims(token).getPayload();
    }

    public String issue(String subject, String tenantId, String username) {
        String secret = props.getChat99JwtSecret();
        if (secret == null || secret.isBlank()) throw new IllegalStateException("未配置 CHAT99_JWT_SECRET");
        Date now = new Date();
        return Jwts.builder().subject(subject).claim("tenantId", tenantId).claim("admin", true)
            .claim("username", username).issuedAt(now)
            .expiration(new Date(now.getTime() + 12 * 60 * 60 * 1000L)).signWith(key(secret)).compact();
    }

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
