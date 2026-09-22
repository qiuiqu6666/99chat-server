package com.chat99.server.chatattachment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ChatNativeVideoMediaToken {

    private ChatNativeVideoMediaToken() {}

    public record Claims(String purpose, String attachmentId, Instant expiresAt) {}

    public static String mint(String purpose, String attachmentId, Instant expiresAt, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("media hmac secret missing");
        }
        long exp = expiresAt.getEpochSecond();
        String payload = "v1|" + purpose + "|" + attachmentId + "|" + exp;
        String sig = hmac(payload, secret);
        return b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + sig;
    }

    public static Claims verify(String token, String secret) {
        if (token == null || secret == null || secret.isBlank()) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        String payloadB64 = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(payloadB64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        if (!hmac(payload, secret).equals(sig)) {
            return null;
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 4 || !"v1".equals(parts[0])) {
            return null;
        }
        if (!"video".equals(parts[1]) && !"thumb".equals(parts[1])) {
            return null;
        }
        if (parts[2] == null || parts[2].isBlank()) {
            return null;
        }
        long exp;
        try {
            exp = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        return new Claims(parts[1], parts[2], Instant.ofEpochSecond(exp));
    }

    private static String hmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return b64(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed");
        }
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
