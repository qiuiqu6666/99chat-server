package com.chat99.robotservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

/**
 * 只做验签 + 过期校验（不查主库用户状态、不查 Redis 会话）。
 * 与主服务 JwtService 使用相同的密钥与算法，token 完全互通。
 */
@Service
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(JwtProperties props) {
        String secret = props.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Required config missing: chat99.jwt.secret (env JWT_SECRET)");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("chat99.jwt.secret must be at least 32 characters");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 验签并返回 userId（sub）。签名/过期非法时抛 {@link io.jsonwebtoken.JwtException}。 */
    public String parseUserId(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return claims.getSubject();
    }
}
