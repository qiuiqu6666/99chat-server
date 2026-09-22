package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/system-config")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminSystemConfigController {

    private final AdminSystemConfigService systemConfigService;

    public AdminSystemConfigController(AdminSystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    @GetMapping("/platform")
    public AdminSystemConfigService.PlatformConfigView platform(Authentication auth) {
        requireSystemConfig(auth);
        return systemConfigService.getPlatform();
    }

    @PutMapping("/platform")
    public AdminSystemConfigService.PlatformConfigView updatePlatform(
            Authentication auth,
            @RequestBody AdminSystemConfigService.UpdatePlatformRequest body) {
        requireSystemConfig(auth);
        return systemConfigService.updatePlatform(body);
    }

    @GetMapping("/push-business")
    public AdminSystemConfigService.PushBusinessConfigView pushBusiness(Authentication auth) {
        requireSystemConfig(auth);
        return systemConfigService.getPushBusiness();
    }

    @PutMapping("/push-business")
    public AdminSystemConfigService.PushBusinessConfigView updatePushBusiness(
            Authentication auth,
            @RequestBody AdminSystemConfigService.UpdatePushBusinessRequest body) {
        requireSystemConfig(auth);
        return systemConfigService.updatePushBusiness(body);
    }

    @GetMapping("/infrastructure")
    public AdminSystemConfigService.InfrastructureConfigView infrastructure(Authentication auth) {
        requireSystemConfig(auth);
        return systemConfigService.getInfrastructure();
    }

    @PutMapping("/infrastructure/{key}")
    public AdminSystemConfigService.InfrastructureItem updateInfrastructure(
            Authentication auth,
            @PathVariable String key,
            @RequestBody AdminSystemConfigService.UpdateInfrastructureRequest body) {
        requireSystemConfig(auth);
        return systemConfigService.updateInfrastructure(key, body.value());
    }

    private static void requireSystemConfig(Authentication auth) {
        AdminPrincipal principal = AdminAccess.require(auth);
        if (!principal.hasPermission("system.config") && !principal.hasPermission("admin.manage")) {
            throw new AdminApiException(
                org.springframework.http.HttpStatus.FORBIDDEN, "forbidden", "forbidden");
        }
    }
}
