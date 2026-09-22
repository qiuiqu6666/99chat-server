/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.im;

import com.chat99.server.push.PushConfigService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ImCallbackVerifier {
    private static final Logger log = LoggerFactory.getLogger(ImCallbackVerifier.class);
    private static final long MAX_SKEW_SECONDS = 60L;
    private final PushConfigService pushConfig;

    public ImCallbackVerifier(PushConfigService pushConfig) {
        this.pushConfig = pushConfig;
    }

    public void verifyQueryToken(String token) {
        String expected = this.pushConfig.getCallbackToken();
        if (expected == null || expected.isBlank()) {
            return;
        }
        if (token == null || !ImCallbackVerifier.constantTimeEquals(expected, token)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    public void verifySignature(String sign, String requestTime) {
        long ts;
        String expectedToken = this.pushConfig.getCallbackToken();
        if (expectedToken == null || expectedToken.isBlank()) {
            return;
        }
        if (sign == null || sign.isBlank() || requestTime == null || requestTime.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        try {
            ts = Long.parseLong(requestTime.trim());
        }
        catch (NumberFormatException e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > 60L) {
            log.warn("im callback sign expired requestTime={} now={}", (Object)requestTime, (Object)now);
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        String expected = ImCallbackVerifier.sha256Hex(expectedToken + requestTime);
        if (!expected.equalsIgnoreCase(sign.trim())) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
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
        }
        catch (Exception e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "SIGN_ERROR");
        }
    }

    public record ImCallbackResponse(Integer ErrorCode, String ErrorInfo, String ActionStatus) {
        public static ImCallbackResponse ok() {
            return new ImCallbackResponse(0, "", "OK");
        }

        public static ImCallbackResponse reject(String errorInfo) {
            return new ImCallbackResponse(1, errorInfo == null ? "REJECTED" : errorInfo, "OK");
        }
    }
}
