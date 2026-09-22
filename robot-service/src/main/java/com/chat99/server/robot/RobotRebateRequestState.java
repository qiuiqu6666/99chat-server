package com.chat99.server.robot;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RobotRebateRequestState(
    String playerGroupId,
    String wxid,
    String playerNo,
    String displayName,
    String playerType,
    BigDecimal balance,
    BigDecimal totalFlow,
    BigDecimal usedFlow,
    BigDecimal remainingFlow,
    BigDecimal rebateRate,
    BigDecimal totalRebate,
    String requestId,
    String settlementType,
    String status,
    String leaseToken,
    LocalDateTime requestedAt,
    BigDecimal flowToConsume,
    BigDecimal rebateAmount,
    BigDecimal expectedBalance,
    BigDecimal expectedTotalFlow,
    BigDecimal expectedUsedFlow,
    String databaseGeneration,
    BigDecimal resultFlow,
    BigDecimal resultAmount,
    String resultCode,
    String resultMessage,
    Boolean retryable) {

    public boolean active() {
        return "PENDING".equals(status) || "PROCESSING".equals(status);
    }

    public boolean pullable(String currentDatabaseGeneration) {
        return active() && currentDatabaseGeneration != null
            && currentDatabaseGeneration.equals(databaseGeneration);
    }
}
