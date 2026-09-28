package com.chat99.server.sync;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ContentDigest {

    private ContentDigest() {}

    static void verify(byte[] data, String declaredHash, Long declaredSize) {
        if (data == null || declaredHash == null || !sha256Hex(data).equals(declaredHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONTENT_MISMATCH");
        }
        if (declaredSize != null && declaredSize.longValue() != data.length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONTENT_MISMATCH");
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
