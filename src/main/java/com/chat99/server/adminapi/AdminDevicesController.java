package com.chat99.server.adminapi;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/devices")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AdminDevicesController {

    private final AdminDevicesService devices;

    public AdminDevicesController(AdminDevicesService devices) {
        this.devices = devices;
    }

    @GetMapping
    public AdminDevicesService.DeviceListResponse list(
        Authentication auth,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
        @RequestParam(name = "user_uid", required = false) String userUid,
        @RequestParam(name = "device_id", required = false) String deviceId,
        @RequestParam(name = "device_fingerprint", required = false) String deviceFingerprint,
        @RequestParam(required = false) String keyword,
        @RequestParam(name = "system_type", required = false) String systemType,
        @RequestParam(name = "app_version", required = false) String appVersion,
        @RequestParam(name = "login_ip", required = false) String loginIp,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String sort) {
        AdminAccess.requirePermission(auth, "user.read");
        return devices.listDevices(
            userUid, deviceId, deviceFingerprint, keyword, systemType,
            appVersion, loginIp, status, page, pageSize);
    }

    @GetMapping("/{id}")
    public AdminDevicesService.DeviceItem detail(
        Authentication auth,
        @PathVariable String id) {
        AdminAccess.requirePermission(auth, "user.read");
        return devices.getDevice(id);
    }

    @GetMapping("/{id}/same-users")
    public AdminDevicesService.SameUsersResponse sameUsers(
        Authentication auth,
        @PathVariable String id,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        AdminAccess.requirePermission(auth, "user.read");
        return devices.listSameUsers(id, page, pageSize);
    }

    @PostMapping("/{id}/ban")
    public AdminDevicesService.OkDeviceActionResult ban(
        Authentication auth,
        HttpServletRequest http,
        @PathVariable String id,
        @RequestBody(required = false) DeviceActionRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        String remark = req == null ? null : req.remark();
        return devices.banDevice(http, admin.username(), id, remark);
    }

    @PostMapping("/{id}/unban")
    public AdminDevicesService.OkDeviceActionResult unban(
        Authentication auth,
        HttpServletRequest http,
        @PathVariable String id,
        @RequestBody(required = false) DeviceActionRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        String remark = req == null ? null : req.remark();
        return devices.unbanDevice(http, admin.username(), id, remark);
    }

    @PostMapping("/{id}/kick")
    public AdminDevicesService.OkDeviceActionResult kick(
        Authentication auth,
        HttpServletRequest http,
        @PathVariable String id,
        @RequestBody(required = false) DeviceActionRequest req) {
        AdminPrincipal admin = AdminAccess.require(auth);
        AdminAccess.requirePermission(auth, "user.write");
        String remark = req == null ? null : req.remark();
        return devices.kickDevice(http, admin.username(), id, remark);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DeviceActionRequest(@Size(max = 255) String remark) {}
}
