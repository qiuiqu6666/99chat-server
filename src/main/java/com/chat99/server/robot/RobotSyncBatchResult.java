package com.chat99.server.robot;

public record RobotSyncBatchResult(String batchId, int accepted, int duplicates) {
}
