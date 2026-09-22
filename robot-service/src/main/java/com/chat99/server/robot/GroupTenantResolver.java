package com.chat99.server.robot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Resolves App {@code X-Group-Id} → enabled machine_code (player_group_id tenant key).
 */
@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class GroupTenantResolver {

    private final RobotMachineService machineService;

    public GroupTenantResolver(RobotMachineService machineService) {
        this.machineService = machineService;
    }

    public String requireMachineCode(String groupIdHeader) {
        return machineService.requireEnabledMachineCodeForGroup(groupIdHeader);
    }
}
