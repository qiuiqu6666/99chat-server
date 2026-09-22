package com.chat99.server.robot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * /api/internal/robot-sync 的唯一入口。
 * robot.enabled=true：本进程处理（单事件 / batch 双协议）；false：转发到 robot-service。
 * 刻意不与 {@link RobotServiceProxyController} 再挂同一路径，避免 Ambiguous handler。
 */
@RestController
public class RobotSyncController {

    private final boolean robotEnabled;
    private final ObjectProvider<RobotSyncService> robotSyncService;
    private final ObjectProvider<RobotMachineService> robotMachineService;
    private final RobotServiceHttpProxy robotServiceHttpProxy;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public RobotSyncController(
            @Value("${robot.enabled:true}") boolean robotEnabled,
            ObjectProvider<RobotSyncService> robotSyncService,
            ObjectProvider<RobotMachineService> robotMachineService,
            RobotServiceHttpProxy robotServiceHttpProxy,
            ObjectMapper objectMapper,
            Validator validator) {
        this.robotEnabled = robotEnabled;
        this.robotSyncService = robotSyncService;
        this.robotMachineService = robotMachineService;
        this.robotServiceHttpProxy = robotServiceHttpProxy;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/api/internal/robot-sync")
    public ResponseEntity<?> sync(
            HttpServletRequest httpRequest,
            @RequestHeader(value = MachineCodeSupport.HEADER_MACHINE_CODE, required = false) String machineCodeHeader)
            throws IOException {

        if (!robotEnabled) {
            return robotServiceHttpProxy.forward(httpRequest);
        }

        byte[] raw = httpRequest.getInputStream().readAllBytes();
        if (raw.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "body required"));
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "invalid json body"));
        }

        RobotMachineService machines = robotMachineService.getIfAvailable();
        RobotSyncService sync = robotSyncService.getIfAvailable();
        if (machines == null || sync == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("success", false, "message", "robot module unavailable"));
        }

        String machineCode;
        try {
            machineCode = machines.requireActiveMachineCode(machineCodeHeader);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "invalid machine code"));
            }
            throw e;
        }

        if (root.path("batch").asBoolean(false)) {
            return handleBatch(sync, root, machineCode);
        }
        return handleSingle(sync, root, machineCode);
    }

    private ResponseEntity<Map<String, Object>> handleBatch(
            RobotSyncService sync, JsonNode root, String machineCode) {
        RobotSyncBatchRequest batch;
        try {
            batch = objectMapper.treeToValue(root, RobotSyncBatchRequest.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "invalid batch body"));
        }

        Set<ConstraintViolation<RobotSyncBatchRequest>> batchViolations = validator.validate(batch);
        if (!batchViolations.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", joinViolations(batchViolations)));
        }

        for (RobotSyncRequest event : batch.getEvents()) {
            Set<ConstraintViolation<RobotSyncRequest>> eventViolations = validator.validate(event);
            if (!eventViolations.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "event " + event.getEventId() + ": " + joinViolations(eventViolations)));
            }
            event.setPlayerGroupId(machineCode);
            event.setRobotId(machineCode);
        }

        RobotSyncBatchResult result = sync.processBatch(batch, MachineCodeSupport.mask(machineCode));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("batchId", result.batchId());
        body.put("accepted", result.accepted());
        body.put("duplicates", result.duplicates());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> handleSingle(
            RobotSyncService sync, JsonNode root, String machineCode) {
        RobotSyncRequest request;
        try {
            request = objectMapper.treeToValue(root, RobotSyncRequest.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "invalid json body"));
        }

        Set<ConstraintViolation<RobotSyncRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String message = joinViolations(violations);
            return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_INPUT",
                "message", message));
        }

        request.setPlayerGroupId(machineCode);
        request.setRobotId(machineCode);

        RobotSyncResult result = sync.process(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.success());
        body.put("updatedCount", result.updatedCount());
        body.put("duplicate", result.duplicated());
        body.put("eventId", request.getEventId());
        body.put("message", result.message());
        return ResponseEntity.ok(body);
    }

    private static <T> String joinViolations(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
            .map(v -> v.getPropertyPath() + ":" + v.getMessage())
            .collect(Collectors.joining(","));
    }
}
