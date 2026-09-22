package com.chat99.server.robot.agent;

import com.chat99.server.robot.MachineCodeSupport;
import com.chat99.server.robot.RobotMachineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class RobotGroupController {

    private final RobotMachineService machineService;

    public RobotGroupController(RobotMachineService machineService) {
        this.machineService = machineService;
    }

    @GetMapping("/me/robot/groups/{groupId}")
    public RobotMachineService.GroupStatusResponse status(
            @PathVariable("groupId") String groupId,
            Authentication auth) {
        requireUser(auth);
        return machineService.getGroupStatus(groupId);
    }

    @PostMapping("/me/robot/groups/{groupId}/bind")
    public RobotMachineService.BindResponse bind(
            @PathVariable("groupId") String groupId,
            @RequestBody BindRequest body,
            Authentication auth) {
        requireUser(auth);
        if (body == null || body.machineCode() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "machineCode required");
        }
        return machineService.bindGroup(body.machineCode(), groupId);
    }

    @PostMapping("/me/robot/groups/{groupId}/enable")
    public RobotMachineService.EnableResponse enable(
            @PathVariable("groupId") String groupId,
            @RequestBody EnableRequest body,
            Authentication auth) {
        requireUser(auth);
        if (body == null || body.robotId() == null || body.robotId().isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "robotId required");
        }
        RobotMachineService.GroupStatusResponse status = machineService.getGroupStatus(groupId);
        if (!status.bound()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "GROUP_NOT_BOUND");
        }
        // App enable uses the already-bound machine; Windows enable uses X-Machine-Code.
        return machineService.enableForBoundGroup(groupId, body.robotId());
    }

    private static String requireUser(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthorized");
        }
        return (String) auth.getPrincipal();
    }

    public record BindRequest(String machineCode) {}

    public record EnableRequest(String robotId) {}
}
