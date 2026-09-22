package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminLogsController {

    private final AdminAccountLoginLogsService adminLoginLogsService;
    private final AdminAuditLogsQueryService auditLogsQueryService;

    public AdminLogsController(AdminAccountLoginLogsService adminLoginLogsService,
                               AdminAuditLogsQueryService auditLogsQueryService) {
        this.adminLoginLogsService = adminLoginLogsService;
        this.auditLogsQueryService = auditLogsQueryService;
    }

    @GetMapping("/login-logs")
    public AdminAccountLoginLogsService.LoginLogsResponse loginLogs(
            Authentication auth,
            @RequestParam(name = "admin_user_id", required = false) String adminUserId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String success,
            @RequestParam(required = false) String ip,
            @RequestParam(name = "login_at_from", required = false) String loginAtFrom,
            @RequestParam(name = "login_at_to", required = false) String loginAtTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(defaultValue = "login_at_desc") String sort) {
        AdminAccess.requirePermission(auth, "admin.manage");
        return adminLoginLogsService.list(
            adminUserId, username, success, ip, loginAtFrom, loginAtTo, page, pageSize, sort);
    }

    @GetMapping("/audit-logs")
    public AdminAuditLogsQueryService.AuditLogsResponse auditLogs(
            Authentication auth,
            @RequestParam(name = "admin_user_id", required = false) String adminUserId,
            @RequestParam(required = false) String action,
            @RequestParam(name = "resource_type", required = false) String resourceType,
            @RequestParam(name = "resource_id", required = false) String resourceId,
            @RequestParam(name = "created_from", required = false) String createdFrom,
            @RequestParam(name = "created_to", required = false) String createdTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(defaultValue = "created_at_desc") String sort) {
        AdminAccess.requirePermission(auth, "admin.manage");
        return auditLogsQueryService.list(
            adminUserId, action, resourceType, resourceId, createdFrom, createdTo, page, pageSize, sort);
    }
}
