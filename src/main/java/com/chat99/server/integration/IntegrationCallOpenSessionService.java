package com.chat99.server.integration;

import com.chat99.server.call.CallSession;
import com.chat99.server.call.CallSessionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationCallOpenSessionService {

    private final CallSessionRepository sessionRepository;

    public IntegrationCallOpenSessionService(CallSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public Map<String, Object> openSession(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        List<CallSession> open = sessionRepository.findOpenSessionsForUser(userId.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        if (open == null || open.isEmpty()) {
            out.put("open", false);
            return out;
        }
        CallSession session = open.get(0);
        out.put("open", true);
        out.put("callId", session.getCallId());
        if (session.getStatus() != null) {
            out.put("status", session.getStatus().name());
        }
        return out;
    }
}
