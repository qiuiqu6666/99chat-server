package com.chat99.server.integration;

import com.chat99.server.group.GroupAccessService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IntegrationGroupLiveAccessService {

    private final GroupAccessService groupAccess;

    public IntegrationGroupLiveAccessService(GroupAccessService groupAccess) {
        this.groupAccess = groupAccess;
    }

    public Map<String, Object> check(String groupId, String userId, String requiredRole) {
        if (groupId == null || groupId.isBlank() || userId == null || userId.isBlank()
            || requiredRole == null || requiredRole.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String role;
        switch (requiredRole.trim().toUpperCase()) {
            case "OWNER" -> {
                groupAccess.requireOwnerRole(groupId.trim(), userId.trim());
                role = "Owner";
            }
            case "ADMIN" -> role = groupAccess.requireAdminRole(groupId.trim(), userId.trim());
            case "MEMBER" -> role = groupAccess.requireMemberRole(groupId.trim(), userId.trim());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("role", role);
        return out;
    }
}
