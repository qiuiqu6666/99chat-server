package com.chat99.server.robot;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class RobotMachineController {

    private final RobotMachineService machineService;
    private final String probeSecret;

    public RobotMachineController(
            RobotMachineService machineService,
            @Value("${robot.probe-secret:}") String probeSecret) {
        this.machineService = machineService;
        this.probeSecret = probeSecret;
    }

    @PostMapping("/api/internal/robot-machines/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody(required = false) RegisterBody body,
            HttpServletRequest request) {
        String label = body == null ? null : body.label();
        RobotMachineService.RegisterResponse created =
            machineService.register(clientIp(request), label);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("machineCode", created.machineCode());
        return ResponseEntity.ok(payload);
    }

    /** 整码注销：级联清租户数据 + 全部群绑定 + 机器码（不可逆）。 */
    @PostMapping("/api/internal/robot-machines/unregister")
    public ResponseEntity<?> unregister(
            @RequestHeader(value = MachineCodeSupport.HEADER_MACHINE_CODE, required = false) String machineCode) {
        try {
            RobotMachineService.UnregisterResponse result = machineService.unregister(machineCode);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("machineCode", result.machineCode());
            payload.put("deletedBindings", result.deletedBindings());
            payload.put("deletedSnapshots", result.deletedSnapshots());
            payload.put("deletedDailySummaries", result.deletedDailySummaries());
            payload.put("deletedUpdownRecords", result.deletedUpdownRecords());
            payload.put("deletedRuntimeStates", result.deletedRuntimeStates());
            payload.put("deletedSyncEvents", result.deletedSyncEvents());
            payload.put("deletedExportTasks", result.deletedExportTasks());
            payload.put("message", "unregistered");
            return ResponseEntity.ok(payload);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", e.getReason() == null ? "invalid machine code" : e.getReason()));
            }
            throw e;
        }
    }

    @PostMapping("/api/internal/robot-machines/bind-group")
    public ResponseEntity<?> bindGroup(
            @RequestHeader(value = MachineCodeSupport.HEADER_MACHINE_CODE, required = false) String machineCode,
            @RequestBody BindBody body) {
        if (body == null) {
            return badRequest("groupId required");
        }
        RobotMachineService.BindResponse bound = machineService.bindGroup(machineCode, body.groupId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("groupId", bound.groupId());
        payload.put("machineCode", bound.machineCode());
        payload.put("enabled", bound.enabled());
        payload.put("robotId", bound.robotId());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/api/internal/robot-machines/enable")
    public ResponseEntity<?> enable(
            @RequestHeader(value = MachineCodeSupport.HEADER_MACHINE_CODE, required = false) String machineCode,
            @RequestBody EnableBody body) {
        if (body == null) {
            return badRequest("groupId and robotId required");
        }
        RobotMachineService.EnableResponse enabled =
            machineService.enable(machineCode, body.groupId(), body.robotId());
        return ResponseEntity.ok(enablePayload(enabled));
    }

    /** Telegram / 主服务：按已绑定群开启（X-Probe-Secret）。 */
    @PostMapping("/api/internal/robot-machines/enable-for-group")
    public ResponseEntity<?> enableForGroup(
            @RequestHeader(value = "X-Probe-Secret", required = false) String probe,
            @RequestBody EnableForGroupBody body) {
        if (!validProbe(probe)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "invalid probe secret"));
        }
        if (body == null) {
            return badRequest("groupId and robotId required");
        }
        RobotMachineService.EnableResponse enabled =
            machineService.enableForBoundGroup(body.groupId(), body.robotId());
        return ResponseEntity.ok(enablePayload(enabled));
    }

    /** Telegram / 主服务：查群绑定（含完整机器码）。 */
    @GetMapping("/api/internal/robot-machines/group-status")
    public ResponseEntity<?> groupStatus(
            @RequestHeader(value = "X-Probe-Secret", required = false) String probe,
            @RequestParam("groupId") String groupId) {
        if (!validProbe(probe)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "invalid probe secret"));
        }
        RobotMachineService.InternalGroupStatus status = machineService.getInternalGroupStatus(groupId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("groupId", status.groupId());
        payload.put("bound", status.bound());
        payload.put("enabled", status.enabled());
        payload.put("robotId", status.robotId());
        payload.put("machineCode", status.machineCode());
        return ResponseEntity.ok(payload);
    }

    private Map<String, Object> enablePayload(RobotMachineService.EnableResponse enabled) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", true);
        payload.put("groupId", enabled.groupId());
        payload.put("machineCode", enabled.machineCode());
        payload.put("robotId", enabled.robotId());
        payload.put("enabled", enabled.enabled());
        return payload;
    }

    private boolean validProbe(String requestSecret) {
        if (requestSecret == null || requestSecret.isBlank()
            || probeSecret == null || probeSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
            requestSecret.getBytes(StandardCharsets.UTF_8),
            probeSecret.getBytes(StandardCharsets.UTF_8));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("success", false, "message", message));
    }

    public record RegisterBody(String label) {}

    public record BindBody(String groupId) {}

    public record EnableBody(String groupId, String robotId) {}

    public record EnableForGroupBody(String groupId, String robotId) {}
}
