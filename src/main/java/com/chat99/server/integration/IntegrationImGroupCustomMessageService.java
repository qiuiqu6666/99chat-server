package com.chat99.server.integration;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationImGroupCustomMessageService {

    private final ImAdminClient imAdminClient;
    private final ImProperties imProperties;

    public IntegrationImGroupCustomMessageService(ImAdminClient imAdminClient, ImProperties imProperties) {
        this.imAdminClient = imAdminClient;
        this.imProperties = imProperties;
    }

    public Map<String, Object> send(String groupId, Map<String, Object> payload) {
        if (groupId == null || groupId.isBlank() || payload == null || payload.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        imAdminClient.sendCustomGroup(imProperties.restAdminAccount(), groupId.trim(), payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }
}
