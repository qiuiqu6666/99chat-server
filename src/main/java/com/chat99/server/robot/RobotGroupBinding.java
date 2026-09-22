package com.chat99.server.robot;

import java.time.LocalDateTime;

public record RobotGroupBinding(
    String imGroupId,
    String machineCode,
    String robotId,
    boolean enabled,
    LocalDateTime boundAt,
    LocalDateTime updatedAt) {}
