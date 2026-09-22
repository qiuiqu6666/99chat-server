package com.chat99.server.robot;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class RobotPlayerDailySummaryRepository {

    private static final String INSERT_SQL = """
        INSERT INTO robot_player_daily_summary (
          event_id, player_group_id, statistics_group_id, business_date, business_timestamp,
          wxid, player_no, nickname, display_name, remark, player_type, balance,
          direct_parent_wxid, direct_parent_no, parent_path, level_no, active,
          total_flow, used_flow, remaining_flow, agent_pending_flow, agent_pending_rebate,
          pending_rebate, total_up, total_down,
          total_profit_loss, rebate_rate, rebate_rate_unit, total_rebate, sync_reason
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;

    public RobotPlayerDailySummaryRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(RobotSyncRequest request, PlayerDailySummaryData data, LocalDate businessDate) {
        return jdbc.update(INSERT_SQL,
            request.getEventId(),
            request.getPlayerGroupId(),
            request.getStatisticsGroupId(),
            businessDate,
            request.getBusinessTimestamp(),
            data.getWxid(),
            data.getPlayerNo(),
            data.getNickname(),
            data.getDisplayName(),
            data.getRemark(),
            data.getPlayerType(),
            decimalOrZero(data.getBalance()),
            data.getDirectParentWxid(),
            data.getDirectParentNo(),
            data.getParentPath(),
            levelOrDefault(data.getLevelNo()),
            activeOrDefault(data.getActive()),
            decimalOrZero(data.getTotalFlow()),
            decimalOrZero(data.getUsedFlow()),
            decimalOrZero(data.getRemainingFlow()),
            // 0 是有效值，必须写入
            decimalOrZero(data.getAgentPendingFlow()),
            decimalOrZero(data.getAgentPendingRebate()),
            decimalOrZero(data.getPendingRebate()),
            decimalOrZero(data.getTotalUp()),
            decimalOrZero(data.getTotalDown()),
            decimalOrZero(data.getTotalProfitLoss()),
            decimalOrZero(data.getRebateRate()),
            rebateRateUnitOrDefault(data.getRebateRateUnit()),
            decimalOrZero(data.getTotalRebate()),
            request.getSyncReason());
    }

    private static BigDecimal decimalOrZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static int levelOrDefault(Integer levelNo) {
        return levelNo != null && levelNo > 0 ? levelNo : 1;
    }

    private static boolean activeOrDefault(Boolean active) {
        return active == null || active;
    }

    private static String rebateRateUnitOrDefault(String unit) {
        return unit != null && !unit.isBlank() ? unit : "per_10000";
    }
}
