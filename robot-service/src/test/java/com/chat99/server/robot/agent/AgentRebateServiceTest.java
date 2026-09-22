package com.chat99.server.robot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentRebateServiceTest {

    @Mock
    private AgentPlayerRepository repository;

    @Test
    void rejectInvalidDescendantScope() {
        var service = new AgentRebateService(repository);
        assertThatThrownBy(() -> service.getDescendants("ABCD-EFGH-IJKL", "ithlxvup5h", "invalid"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectDescendantHistoryRangeOver93Days() {
        var service = new AgentRebateService(repository);
        assertThatThrownBy(() -> service.getHistoryRebate(
            "ABCD-EFGH-IJKL",
            "ithlxvup5h",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 7, 1)))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void pendingRebateUsesPer10000Rate() {
        var value = AgentRebateMath.pendingRebate(
            new java.math.BigDecimal("4000"),
            new java.math.BigDecimal("35"));
        assertThat(value).isEqualByComparingTo("14.0000");
    }

    @Test
    void pendingRebateFloorMatchesPersonalFormula() {
        var value = AgentRebateMath.pendingRebateFloor(
            new java.math.BigDecimal("20000"),
            new java.math.BigDecimal("300"));
        assertThat(value).isEqualByComparingTo("600");
        // 902 * 300 / 10000 = 27.06 -> floor to yuan = 27
        assertThat(AgentRebateMath.pendingRebateFloor(
            new java.math.BigDecimal("902"),
            new java.math.BigDecimal("300")))
            .isEqualByComparingTo("27");
    }

    @Test
    void isAgentWhenRebateRatePositive() {
        assertThat(AgentRebateMath.isAgentByRebateRate(new java.math.BigDecimal("300"))).isTrue();
        assertThat(AgentRebateMath.isAgentByRebateRate(java.math.BigDecimal.ZERO)).isFalse();
        assertThat(AgentRebateMath.isAgentByRebateRate(null)).isFalse();
    }

    @Test
    void differentialRebateUsesRateDiffFloorToInteger() {
        assertThat(AgentRebateCalculator.calculateRateDiff(
            new java.math.BigDecimal("300"),
            new java.math.BigDecimal("200")))
            .isEqualByComparingTo("100");
        assertThat(AgentRebateCalculator.calculateRateDiff(
            new java.math.BigDecimal("200"),
            new java.math.BigDecimal("300")))
            .isEqualByComparingTo("0");
        assertThat(AgentRebateCalculator.calculateDifferentialRebate(
            new java.math.BigDecimal("10000"),
            new java.math.BigDecimal("300"),
            new java.math.BigDecimal("200")))
            .isEqualByComparingTo("100");
    }
}
