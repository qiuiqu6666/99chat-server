package com.chat99.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class IntegrationImGroupCustomMessageServiceTest {

    @Mock ImAdminClient imAdminClient;

    IntegrationImGroupCustomMessageService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationImGroupCustomMessageService(
            imAdminClient, new ImProperties(0, "administrator", null, null, null));
    }

    @Test
    void sendsAsRestAdmin() {
        Map<String, Object> payload = Map.of("businessID", "group_live_started", "liveSessionId", "gl_1");
        Map<String, Object> out = service.send("m123", payload);
        assertThat(out.get("ok")).isEqualTo(true);
        verify(imAdminClient).sendCustomGroup("administrator", "m123", payload);
    }

    @Test
    void rejectsEmptyPayload() {
        assertThatThrownBy(() -> service.send("m123", Map.of()))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(imAdminClient);
    }
}
