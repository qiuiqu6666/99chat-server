package com.chat99.server.robot.agent;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AgentReportExportTask(
    long id,
    String taskNo,
    String requesterUserId,
    String playerGroupId,
    String agentPlayerNo,
    String agentName,
    LocalDate startDate,
    LocalDate endDate,
    String fileType,
    boolean includeAgentDetail,
    boolean includePlayerDetail,
    String taskStatus,
    int progress,
    String filePath,
    String fileName,
    Long fileSize,
    String contentType,
    int rowCount,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime expiresAt) {

    public boolean ownedBy(String userId) {
        return requesterUserId != null && requesterUserId.equals(userId);
    }

    public boolean completed() {
        return "COMPLETED".equals(taskStatus);
    }

    public boolean failed() {
        return "FAILED".equals(taskStatus);
    }
}
