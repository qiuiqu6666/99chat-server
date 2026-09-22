package com.chat99.server.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationAuthService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationAuthService.class);
    private static final long MAX_SKEW_SECONDS = 60L;

    private final IntegrationApiProperties properties;

    public IntegrationAuthService(IntegrationApiProperties properties) {
        this.properties = properties;
    }

    public void verify(String headerToken, String sign, String requestTime) {
        String expected = properties.apiToken();
        if (!properties.configured()) {
            log.warn("integration API token not configured");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_NOT_CONFIGURED");
        }
        if (sign != null && !sign.isBlank()) {
            verifySignature(sign, requestTime, expected);
            return;
        }
        if (headerToken != null && expected.equals(headerToken.trim())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    private static void verifySignature(String sign, String requestTime, String token) {
        if (requestTime == null || requestTime.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        long ts;
        try {
            ts = Long.parseLong(requestTime.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > MAX_SKEW_SECONDS) {
            log.warn("integration sign expired requestTime={} now={}", requestTime, now);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        String expected = sha256Hex(token + requestTime);
        if (!expected.equalsIgnoreCase(sign.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
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
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "SIGN_ERROR");
        }
    }
}
