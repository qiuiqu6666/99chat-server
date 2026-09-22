/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.wallet;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WalletSecretCipher {
    public static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public WalletSecretCipher(@Value(value="${chat99.wallet.data-encryption-key:}") String rawKey) {
        this.key = WalletSecretCipher.parseKey(rawKey);
    }

    public boolean enabled() {
        return this.key != null;
    }

    public String seal(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (!this.enabled()) {
            return plaintext;
        }
        if (WalletSecretCipher.isSealed(plaintext)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[12];
            this.secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(1, (Key)this.key, new GCMParameterSpec(128, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException("wallet secret encrypt failed", e);
        }
    }

    public String open(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        if (!WalletSecretCipher.isSealed(stored)) {
            return stored;
        }
        if (!this.enabled()) {
            throw new IllegalStateException("encrypted wallet secret found but WALLET_DATA_ENCRYPTION_KEY is not configured");
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length <= 12) {
                throw new IllegalStateException("invalid wallet secret ciphertext");
            }
            byte[] iv = new byte[12];
            System.arraycopy(payload, 0, iv, 0, 12);
            byte[] cipherText = new byte[payload.length - 12];
            System.arraycopy(payload, 12, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(2, (Key)this.key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalStateException("wallet secret decrypt failed", e);
        }
    }

    public static boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static SecretKey parseKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(rawKey.trim());
            if (decoded.length != 16 && decoded.length != 24 && decoded.length != 32) {
                throw new IllegalStateException("WALLET_DATA_ENCRYPTION_KEY must be Base64 of 16/24/32 bytes");
            }
            return new SecretKeySpec(decoded, "AES");
        }
        catch (IllegalArgumentException e) {
            throw new IllegalStateException("WALLET_DATA_ENCRYPTION_KEY is not valid Base64", e);
        }
    }
}
