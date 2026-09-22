package com.chat99.server.robot.agent;

import com.chat99.server.robot.GroupTenantResolver;
import com.chat99.server.robot.MachineCodeSupport;
import com.chat99.server.robot.RobotRebateTaskService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@RestController
public class PlayerRebateController {

    private final RobotRebateTaskService rebateTaskService;
    private final GroupTenantResolver groupTenantResolver;

    public PlayerRebateController(
            RobotRebateTaskService rebateTaskService,
            GroupTenantResolver groupTenantResolver) {
        this.rebateTaskService = rebateTaskService;
        this.groupTenantResolver = groupTenantResolver;
    }

    @PostMapping("/me/rebate/apply")
    public RobotRebateTaskService.ApplyResponse apply(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateTaskService.applyPlayer(machineCode, requireUser(auth));
    }

    @GetMapping("/me/rebate/apply/status")
    public RobotRebateTaskService.ApplyStatusResponse status(
            @RequestHeader(value = MachineCodeSupport.HEADER_GROUP_ID, required = false) String groupId,
            Authentication auth) {
        String machineCode = groupTenantResolver.requireMachineCode(groupId);
        return rebateTaskService.getApplyStatus(machineCode, requireUser(auth));
    }

    private static String requireUser(Authentication auth) {
        return (String) auth.getPrincipal();
    }
}
