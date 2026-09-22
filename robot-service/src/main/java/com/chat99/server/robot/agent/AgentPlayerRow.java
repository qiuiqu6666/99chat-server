package com.chat99.server.robot.agent;

import java.math.BigDecimal;

public record AgentPlayerRow(
    String wxid,
    String playerNo,
    String nickname,
    String displayName,
    String remark,
    String playerType,
    BigDecimal balance,
    String directParentWxid,
    String directParentNo,
    String parentPath,
    int levelNo,
    BigDecimal totalFlow,
    BigDecimal usedFlow,
    BigDecimal remainingFlow,
    BigDecimal agentPendingFlow,
    BigDecimal agentPendingRebate,
    BigDecimal totalUp,
    BigDecimal totalDown,
    BigDecimal totalProfitLoss,
    BigDecimal rebateRate,
    String rebateRateUnit,
    BigDecimal totalRebate,
    long sourceUpdatedAt) {

    public boolean realPlayer() {
        return !"1".equals(playerType);
    }
}
