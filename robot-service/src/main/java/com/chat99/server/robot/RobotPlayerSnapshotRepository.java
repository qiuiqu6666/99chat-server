package com.chat99.server.robot;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class RobotPlayerSnapshotRepository {

    private static final String FIND_SQL = """
        SELECT source_updated_at, last_event_id
        FROM robot_player_snapshot
        WHERE player_group_id = ? AND database_generation = ? AND wxid = ?
        LIMIT 1
        """;

    private static final String INSERT_SQL = """
        INSERT INTO robot_player_snapshot (
          player_group_id, database_generation, statistics_group_id, wxid, player_no, nickname, display_name, remark,
          player_type, balance, direct_parent_wxid, direct_parent_no, parent_path, level_no, active,
          total_flow, used_flow, remaining_flow, agent_pending_flow, agent_pending_rebate,
          total_up, total_down, total_profit_loss,
          rebate_rate, rebate_rate_unit, total_rebate, source_updated_at, last_event_id, last_sync_reason
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String UPDATE_SQL = """
        UPDATE robot_player_snapshot SET
          statistics_group_id = ?,
          player_no = ?,
          nickname = ?,
          display_name = ?,
          remark = ?,
          player_type = ?,
          balance = ?,
          direct_parent_wxid = ?,
          direct_parent_no = ?,
          parent_path = ?,
          level_no = ?,
          active = ?,
          total_flow = ?,
          used_flow = ?,
          remaining_flow = ?,
          agent_pending_flow = ?,
          agent_pending_rebate = ?,
          total_up = ?,
          total_down = ?,
          total_profit_loss = ?,
          rebate_rate = ?,
          rebate_rate_unit = ?,
          total_rebate = ?,
          source_updated_at = ?,
          last_event_id = ?,
          last_sync_reason = ?
        WHERE player_group_id = ? AND database_generation = ? AND wxid = ?
        """;

    private final JdbcTemplate jdbc;

    public RobotPlayerSnapshotRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SnapshotVersion(long sourceUpdatedAt, String lastEventId) {}

    public Optional<SnapshotVersion> findVersion(String playerGroupId, String databaseGeneration, String wxid) {
        return jdbc.query(
                FIND_SQL,
                (rs, rowNum) -> new SnapshotVersion(
                    rs.getLong("source_updated_at"),
                    rs.getString("last_event_id")),
                playerGroupId,
                databaseGeneration,
                wxid)
            .stream()
            .findFirst();
    }

    public Optional<Long> findSourceUpdatedAt(String playerGroupId, String databaseGeneration, String wxid) {
        return findVersion(playerGroupId, databaseGeneration, wxid).map(SnapshotVersion::sourceUpdatedAt);
    }

    public int insert(RobotSyncRequest request, PlayerSnapshotData data, String databaseGeneration) {
        return jdbc.update(INSERT_SQL,
            request.getPlayerGroupId(),
            databaseGeneration,
            request.getStatisticsGroupId(),
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
            // 0 是有效值，必须写入（代理结算后清零依赖此语义）
            decimalOrZero(data.getAgentPendingFlow()),
            decimalOrZero(data.getAgentPendingRebate()),
            decimalOrZero(data.getTotalUp()),
            decimalOrZero(data.getTotalDown()),
            decimalOrZero(data.getTotalProfitLoss()),
            decimalOrZero(data.getRebateRate()),
            rebateRateUnitOrDefault(data.getRebateRateUnit()),
            decimalOrZero(data.getTotalRebate()),
            request.getSourceUpdatedAt(),
            request.getEventId(),
            request.getSyncReason());
    }

    public int update(RobotSyncRequest request, PlayerSnapshotData data, String databaseGeneration) {
        return jdbc.update(UPDATE_SQL,
            request.getStatisticsGroupId(),
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
            // 0 是有效值，必须写入（代理结算后清零依赖此语义）
            decimalOrZero(data.getAgentPendingFlow()),
            decimalOrZero(data.getAgentPendingRebate()),
            decimalOrZero(data.getTotalUp()),
            decimalOrZero(data.getTotalDown()),
            decimalOrZero(data.getTotalProfitLoss()),
            decimalOrZero(data.getRebateRate()),
            rebateRateUnitOrDefault(data.getRebateRateUnit()),
            decimalOrZero(data.getTotalRebate()),
            request.getSourceUpdatedAt(),
            request.getEventId(),
            request.getSyncReason(),
            request.getPlayerGroupId(),
            databaseGeneration,
            data.getWxid());
    }

    public int invalidateOldGenerations(String robotId, String currentDatabaseGeneration) {
        return jdbc.update(
            """
            UPDATE robot_player_snapshot
            SET active = 0
            WHERE player_group_id = ?
              AND database_generation <> ?
              AND active = 1
            """,
            robotId,
            currentDatabaseGeneration);
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
