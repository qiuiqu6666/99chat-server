package com.chat99.server.adminapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AdminUserGenerationCryptoTest {

    private final AdminUserGenerationCrypto crypto = new AdminUserGenerationCrypto(
        new AdminUserGenerationProperties("test-key-with-enough-entropy", 1000, 30));

    @Test
    void encryptsWithRandomIvAndDecrypts() {
        String first = crypto.encrypt("Password123!");
        String second = crypto.encrypt("Password123!");

        assertNotEquals(first, second);
        assertEquals("Password123!", crypto.decrypt(first));
        assertEquals("Password123!", crypto.decrypt(second));
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = crypto.encrypt("Password123!");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

        assertThrows(IllegalStateException.class, () -> crypto.decrypt(tampered));
    }
}
