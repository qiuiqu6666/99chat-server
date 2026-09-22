package com.chat99.server.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class IntegrationAuthServiceTest {

    @Test
    void acceptMatchingToken() {
        var service = new IntegrationAuthService(new IntegrationApiProperties("secret-token"));
        assertDoesNotThrow(() -> service.verify("secret-token", null, null));
    }

    @Test
    void rejectMissingTokenWhenConfigured() {
        var service = new IntegrationAuthService(new IntegrationApiProperties("secret-token"));
        assertThrows(ResponseStatusException.class, () -> service.verify("wrong", null, null));
    }

    @Test
    void rejectWhenNotConfigured() {
        var service = new IntegrationAuthService(new IntegrationApiProperties(""));
        assertThrows(ResponseStatusException.class, () -> service.verify("any", null, null));
    }
}
