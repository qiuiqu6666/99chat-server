package com.chat99.server.adminapi;

import com.chat99.server.common.ClientContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {

    private final AdminAuditLogRepository repository;
    private final ClientContext clientContext;
    private final ObjectMapper json = new ObjectMapper();

    public AdminAuditService(AdminAuditLogRepository repository, ClientContext clientContext) {
        this.repository = repository;
        this.clientContext = clientContext;
    }

    public void log(HttpServletRequest http, String adminUsername, String action,
                    String targetUserId, Map<String, Object> detail) {
        AdminAuditLog row = new AdminAuditLog();
        row.setAdminUsername(adminUsername);
        row.setAction(action);
        row.setTargetUserId(targetUserId);
        row.setIp(http != null ? clientContext.ip(http) : null);
        if (detail != null && !detail.isEmpty()) {
            try {
                row.setDetailJson(json.writeValueAsString(detail));
            } catch (JsonProcessingException ignored) {
                row.setDetailJson(detail.toString());
            }
        }
        repository.save(row);
    }
}
