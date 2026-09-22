package com.chat99.server.robot.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 下级级差反水：
 * floor(下级可结算流水 × (代理比例 - 直属下级比例) ÷ 10000)
 */
public final class AgentRebateCalculator {

    private static final BigDecimal RATE_BASE = new BigDecimal("10000");

    private AgentRebateCalculator() {}

    public static BigDecimal calculateRateDiff(BigDecimal agentRate, BigDecimal directChildRate) {
        BigDecimal safeAgentRate = agentRate == null ? BigDecimal.ZERO : agentRate;
        BigDecimal safeChildRate = directChildRate == null ? BigDecimal.ZERO : directChildRate;
        return safeAgentRate.subtract(safeChildRate).max(BigDecimal.ZERO);
    }

    public static BigDecimal calculateDifferentialRebate(
            BigDecimal settleFlow,
            BigDecimal agentRate,
            BigDecimal directChildRate) {
        BigDecimal safeFlow = settleFlow == null ? BigDecimal.ZERO : settleFlow;
        if (safeFlow.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal rateDiff = calculateRateDiff(agentRate, directChildRate);
        if (rateDiff.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        return safeFlow
            .multiply(rateDiff)
            .divide(RATE_BASE, 8, RoundingMode.DOWN)
            .setScale(0, RoundingMode.DOWN);
    }
}
