package com.chat99.server.robot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class RobotSyncController {

    private final RobotSyncService robotSyncService;
    private final RobotMachineService robotMachineService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public RobotSyncController(RobotSyncService robotSyncService,
                               RobotMachineService robotMachineService,
                               ObjectMapper objectMapper,
                               Validator validator) {
        this.robotSyncService = robotSyncService;
        this.robotMachineService = robotMachineService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @PostMapping("/api/internal/robot-sync")
    public ResponseEntity<Map<String, Object>> sync(
            @RequestHeader(value = MachineCodeSupport.HEADER_MACHINE_CODE, required = false) String machineCodeHeader,
            HttpServletRequest httpRequest) throws IOException {

        byte[] raw = httpRequest.getInputStream().readAllBytes();
        if (raw.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "body required"));
        }

        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "invalid json body"));
        }

        String machineCode;
        try {
            machineCode = robotMachineService.requireActiveMachineCode(machineCodeHeader);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "message", "invalid machine code"));
            }
            throw e;
        }

        if (root.path("batch").asBoolean(false)) {
            return handleBatch(root, machineCode);
        }
        return handleSingle(root, machineCode);
    }

    private ResponseEntity<Map<String, Object>> handleBatch(
            com.fasterxml.jackson.databind.JsonNode root, String machineCode) {
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
            // Force tenant key = machine code.
            event.setPlayerGroupId(machineCode);
            event.setRobotId(machineCode);
        }

        RobotSyncBatchResult result = robotSyncService.processBatch(
            batch, MachineCodeSupport.mask(machineCode));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("batchId", result.batchId());
        body.put("accepted", result.accepted());
        body.put("duplicates", result.duplicates());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> handleSingle(
            com.fasterxml.jackson.databind.JsonNode root, String machineCode) {
        RobotSyncRequest request;
        try {
            request = objectMapper.treeToValue(root, RobotSyncRequest.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "invalid json body"));
        }

        Set<ConstraintViolation<RobotSyncRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", joinViolations(violations)));
        }

        request.setPlayerGroupId(machineCode);
        request.setRobotId(machineCode);

        RobotSyncResult result = robotSyncService.process(request);
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
