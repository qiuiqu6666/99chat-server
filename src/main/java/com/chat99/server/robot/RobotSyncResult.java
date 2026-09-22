package com.chat99.server.robot;

public record RobotSyncResult(
    boolean success,
    boolean duplicated,
    int updatedCount,
    String message) {

    public static RobotSyncResult accepted(int updatedCount) {
        return new RobotSyncResult(true, false, updatedCount, "accepted");
    }

    public static RobotSyncResult duplicate() {
        return new RobotSyncResult(true, true, 0, "already processed");
    }

    public static RobotSyncResult notUpdated(String message) {
        return new RobotSyncResult(false, false, 0, message);
    }
}
