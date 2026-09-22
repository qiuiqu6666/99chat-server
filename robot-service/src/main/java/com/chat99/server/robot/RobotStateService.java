package com.chat99.server.robot;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Service
public class RobotStateService {

    private final RobotRuntimeStateRepository repository;

    public RobotStateService(RobotRuntimeStateRepository repository) {
        this.repository = repository;
    }

    public RobotRuntimeState requireReady(String robotId) {
        RobotRuntimeState state = repository.findByRobotId(robotId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "ROBOT_NOT_READY"));
        if (!state.ready()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROBOT_NOT_READY");
        }
        return state;
    }

    public RobotRuntimeState requireReady(String robotId, String databaseGeneration) {
        RobotRuntimeState state = requireReady(robotId);
        if (databaseGeneration == null || databaseGeneration.isBlank()
            || !databaseGeneration.trim().equals(state.databaseGeneration())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_DATABASE_GENERATION");
        }
        return state;
    }
}
