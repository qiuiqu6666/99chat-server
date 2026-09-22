package com.chat99.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chat99.server.call.CallSession;
import com.chat99.server.call.CallSessionRepository;
import com.chat99.server.call.CallSessionStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IntegrationCallOpenSessionServiceTest {

    @Mock CallSessionRepository sessionRepository;

    IntegrationCallOpenSessionService service;

    @BeforeEach
    void setUp() {
        service = new IntegrationCallOpenSessionService(sessionRepository);
    }

    @Test
    void noOpenSession() {
        when(sessionRepository.findOpenSessionsForUser("user01")).thenReturn(List.of());
        Map<String, Object> out = service.openSession("user01");
        assertThat(out.get("open")).isEqualTo(false);
        assertThat(out).doesNotContainKey("callId");
    }

    @Test
    void openAnsweredSession() {
        CallSession session = new CallSession();
        session.setCallId("call_abc");
        session.setStatus(CallSessionStatus.ANSWERED);
        when(sessionRepository.findOpenSessionsForUser("user01")).thenReturn(List.of(session));
        Map<String, Object> out = service.openSession("user01");
        assertThat(out.get("open")).isEqualTo(true);
        assertThat(out.get("callId")).isEqualTo("call_abc");
        assertThat(out.get("status")).isEqualTo("ANSWERED");
    }
}
