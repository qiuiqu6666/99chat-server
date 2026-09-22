package com.chat99.server.robot;

import java.time.LocalDateTime;

public record RobotMachine(
    String machineCode,
    String status,
    String label,
    LocalDateTime createdAt,
    LocalDateTime lastSeenAt) {

    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
