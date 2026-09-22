package com.chat99.server.adminapi;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.util.encoders.Base32;

final class TotpUtil {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int SKEW_WINDOWS = 2;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpUtil() {}

    static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return new String(Base32.encode(bytes), StandardCharsets.US_ASCII).replace("=", "");
    }

    static boolean verify(String secretBase32, String code) {
        if (secretBase32 == null || secretBase32.isBlank() || code == null) {
            return false;
        }
        String normalized = code.replaceAll("\\s", "");
        if (!normalized.matches("\\d{6}")) {
            return false;
        }
        byte[] key = Base32.decode(normalizeSecret(secretBase32));
        long counter = System.currentTimeMillis() / 1000 / TIME_STEP_SECONDS;
        for (int i = -SKEW_WINDOWS; i <= SKEW_WINDOWS; i++) {
            if (codeEquals(generateCode(key, counter + i), normalized)) {
                return true;
            }
        }
        return false;
    }

    static String otpAuthUri(String secret, String issuer, String account) {
        return "otpauth://totp/"
            + urlEncode(issuer + ":" + account)
            + "?secret=" + secret
            + "&issuer=" + urlEncode(issuer)
            + "&digits=6&period=30";
    }

    private static boolean codeEquals(int generated, String normalizedInput) {
        return String.format("%06d", generated).equals(normalizedInput);
    }

    private static String normalizeSecret(String secret) {
        String s = secret.toUpperCase().replace(" ", "");
        int pad = (8 - s.length() % 8) % 8;
        return s + "=".repeat(pad);
    }

    private static int generateCode(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
            return binary % 1_000_000;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
