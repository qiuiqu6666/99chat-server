package com.chat99.server.robot;

import java.time.LocalDateTime;

public record RobotRuntimeState(
    String robotId,
    String databaseGeneration,
    String syncStatus,
    int playerCount,
    LocalDateTime syncStartedAt,
    LocalDateTime syncCompletedAt,
    LocalDateTime updatedAt) {

    public static final String STATUS_SYNCING = "SYNCING";
    public static final String STATUS_READY = "READY";

    public boolean ready() {
        return STATUS_READY.equals(syncStatus);
    }

    public boolean syncing() {
        return STATUS_SYNCING.equals(syncStatus);
    }
}
