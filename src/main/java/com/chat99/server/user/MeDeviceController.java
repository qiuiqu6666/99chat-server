package com.chat99.server.user;

import com.chat99.server.security.JwtService;
import com.chat99.server.security.UserSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MeDeviceController {

    private final UserDeviceAppService deviceAppService;
    private final UserLoginLogAppService loginLogAppService;
    private final UserSessionService sessionService;
    private final JwtService jwtService;

    public MeDeviceController(UserDeviceAppService deviceAppService,
                              UserLoginLogAppService loginLogAppService,
                              UserSessionService sessionService,
                              JwtService jwtService) {
        this.deviceAppService = deviceAppService;
        this.loginLogAppService = loginLogAppService;
        this.sessionService = sessionService;
        this.jwtService = jwtService;
    }

    @GetMapping("/me/devices")
    public UserDeviceAppService.DeviceListResponse listDevices(Authentication auth,
                                                               HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        String currentDeviceId = currentDeviceId(http).orElse("");
        return deviceAppService.listDevices(userId, currentDeviceId);
    }

    @GetMapping("/me/login-logs")
    public UserLoginLogAppService.LoginLogListResponse loginLogs(
        Authentication auth,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        String userId = (String) auth.getPrincipal();
        return loginLogAppService.listLoginLogs(userId, page, pageSize);
    }

    @PostMapping("/me/devices/{deviceId}/kick")
    public Map<String, Object> kickDevice(Authentication auth,
                                          HttpServletRequest http,
                                          @PathVariable String deviceId) {
        String userId = (String) auth.getPrincipal();
        String normalizedTarget = normalizeDeviceId(deviceId);
        String currentDeviceId = currentDeviceId(http).orElse("");
        if (!currentDeviceId.isEmpty() && normalizedTarget.equalsIgnoreCase(currentDeviceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CANNOT_KICK_SELF");
        }
        if (!deviceAppService.deviceBelongsToUser(userId, normalizedTarget)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND");
        }
        Set<String> protect = currentDeviceId.isBlank()
            ? Set.of()
            : Set.of(currentDeviceId);
        sessionService.revokeDevice(userId, normalizedTarget, true, protect);
        return Map.of("ok", true, "deviceId", normalizedTarget);
    }

    @PostMapping("/me/devices/kick-others")
    public Map<String, Object> kickOthers(Authentication auth, HttpServletRequest http) {
        String userId = (String) auth.getPrincipal();
        Optional<String> currentDeviceId = currentDeviceId(http);
        if (currentDeviceId.isEmpty() || currentDeviceId.get().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CURRENT_DEVICE_UNKNOWN");
        }
        int kickedCount = sessionService.revokeAllExcept(userId, currentDeviceId.get());
        return Map.of("ok", true, "kickedCount", kickedCount);
    }

    private Optional<String> currentDeviceId(HttpServletRequest http) {
        String header = http.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return jwtService.parseDeviceId(header.substring(7));
    }

    private static String normalizeDeviceId(String deviceId) {
        return deviceId == null ? "" : deviceId.trim();
    }
}
