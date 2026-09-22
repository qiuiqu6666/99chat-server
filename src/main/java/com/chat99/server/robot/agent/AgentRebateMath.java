package com.chat99.server.robot.agent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class AgentRebateMath {

    private static final int MONEY_SCALE = 4;
    private static final int RATE_SCALE = 8;

    private AgentRebateMath() {}

    public static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return zero();
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal sumMoney(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(money(value));
        }
        return money(total);
    }

    public static BigDecimal pendingRebate(BigDecimal remainingFlow, BigDecimal rebateRate) {
        if (remainingFlow == null || rebateRate == null) {
            return zero();
        }
        return money(remainingFlow.multiply(rebateRate).divide(BigDecimal.valueOf(10_000L), RATE_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 个人待反水：floor(remainingFlow × rebateRate ÷ 10000)，取整到元（与级差计算器一致）。
     */
    public static BigDecimal pendingRebateFloor(BigDecimal remainingFlow, BigDecimal rebateRate) {
        if (remainingFlow == null || rebateRate == null) {
            return zero();
        }
        if (remainingFlow.signum() <= 0 || rebateRate.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return remainingFlow
            .multiply(rebateRate)
            .divide(BigDecimal.valueOf(10_000L), RATE_SCALE, RoundingMode.DOWN)
            .setScale(0, RoundingMode.DOWN);
    }

    /** 代理判定：有返水比例（> 0）即为代理。 */
    public static boolean isAgentByRebateRate(BigDecimal rebateRate) {
        return rebateRate != null && rebateRate.compareTo(BigDecimal.ZERO) > 0;
    }
}
