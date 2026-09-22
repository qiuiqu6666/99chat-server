package com.chat99.server.lifepayment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class LifePaymentWorkerTokenSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "lpw_";

    private LifePaymentWorkerTokenSupport() {
    }

    public static String generateToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(PREFIX.length() + bytes.length * 2);
        sb.append(PREFIX);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String hashToken(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return "";
        }
        return sha256Hex(plainToken.trim());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
