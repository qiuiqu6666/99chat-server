package com.chat99.server.robot;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class RobotRebateTaskController {

    private final RobotMachineService machineService;
    private final RobotRebateTaskService rebateTaskService;

    public RobotRebateTaskController(RobotMachineService machineService, RobotRebateTaskService rebateTaskService) {
        this.machineService = machineService;
        this.rebateTaskService = rebateTaskService;
    }

    @PostMapping("/api/internal/robot-rebate-tasks/pull")
    public ResponseEntity<?> pull(
            @RequestHeader(value = MachineCodeSupport.HEADER_MACHINE_CODE, required = false) String machineCodeHeader,
            @RequestBody(required = false) PullBody body) {
        String machineCode;
        try {
            machineCode = machineService.requireActiveMachineCode(machineCodeHeader);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return unauthorized();
            }
            throw e;
        }
        Integer limit = body == null ? null : body.limit();
        String databaseGeneration = body == null ? null : body.databaseGeneration();
        RobotRebateTaskService.PullResponse response = rebateTaskService.pull(
            new RobotRebateTaskService.PullRequest(machineCode, databaseGeneration, limit));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", response.success());
        payload.put("data", response.data());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/api/internal/robot-rebate-tasks/result")
    public ResponseEntity<?> result(
            @RequestHeader(value = MachineCodeSupport.HEADER_MACHINE_CODE, required = false) String machineCodeHeader,
            @RequestBody ResultBody body) {
        String machineCode;
        try {
            machineCode = machineService.requireActiveMachineCode(machineCodeHeader);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return unauthorized();
            }
            throw e;
        }
        RobotRebateTaskService.ResultResponse response = rebateTaskService.reportResult(
            machineCode,
            new RobotRebateTaskService.ResultRequest(
                body.taskId(),
                body.leaseToken(),
                body.robotId(),
                body.databaseGeneration(),
                body.settlementType(),
                body.applicantWxid(),
                body.success(),
                body.retryable(),
                body.resultCode(),
                body.resultMessage(),
                body.consumedFlow(),
                body.rebateAmount()));
        return ResponseEntity.ok(Map.of(
            "success", response.success(),
            "message", response.message()));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("success", false, "message", "invalid machine code"));
    }

    public record PullBody(String robotId, String databaseGeneration, Integer limit) {}

    public record ResultBody(
        String taskId,
        String leaseToken,
        String robotId,
        String databaseGeneration,
        String settlementType,
        String applicantWxid,
        Boolean success,
        Boolean retryable,
        String resultCode,
        String resultMessage,
        java.math.BigDecimal consumedFlow,
        java.math.BigDecimal rebateAmount) {}
}
