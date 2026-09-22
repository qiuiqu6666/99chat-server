package com.chat99.server.robot.agent;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AgentDailySummaryRow(
    LocalDate businessDate,
    String wxid,
    String playerNo,
    String displayName,
    String playerType,
    String directParentWxid,
    BigDecimal balance,
    BigDecimal totalFlow,
    BigDecimal totalUp,
    BigDecimal totalDown,
    BigDecimal totalProfitLoss,
    BigDecimal totalRebate,
    BigDecimal pendingRebate,
    BigDecimal rebateRate,
    BigDecimal agentPendingFlow,
    BigDecimal agentPendingRebate) {

    public boolean realPlayer() {
        return !"1".equals(playerType);
    }
}
