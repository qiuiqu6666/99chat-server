package com.chat99.server.adminapi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TotpUtilTest {

    @Test
    void roundtripGeneratedSecret() {
        String secret = TotpUtil.generateSecret();
        byte[] key = org.bouncycastle.util.encoders.Base32.decode(
            secret.toUpperCase() + "=".repeat((8 - secret.length() % 8) % 8));
        long counter = System.currentTimeMillis() / 1000 / 30;
        int code = TotpUtilRoundtripHelper.codeFor(key, counter);
        assertTrue(TotpUtil.verify(secret, String.format("%06d", code)));
    }
}
