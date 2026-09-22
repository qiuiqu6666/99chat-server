package com.chat99.server.robot.agent;

import com.chat99.server.robot.RobotRuntimeStateRepository;
import com.chat99.server.robot.RobotSyncSupport;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class AgentPlayerRepository {

    private static final String SNAPSHOT_COLUMNS = """
        wxid, player_no, nickname, display_name, remark, player_type, balance,
        direct_parent_wxid, direct_parent_no, parent_path, level_no,
        total_flow, used_flow, remaining_flow, agent_pending_flow, agent_pending_rebate,
        total_up, total_down, total_profit_loss,
        rebate_rate, rebate_rate_unit, total_rebate, source_updated_at
        """;

    private static final String VISIBLE_SNAPSHOT_SQL = """
        SELECT %s
        FROM robot_player_snapshot
        WHERE player_group_id = ?
          AND database_generation = ?
          AND active = 1
          AND player_type <> '1'
          AND (
            wxid = ?
            OR direct_parent_wxid = ?
            OR parent_path LIKE CONCAT('%%', ?, '###%%')
          )
        """.formatted(SNAPSHOT_COLUMNS);

    private static final String PLAYER_SQL = """
        SELECT %s
        FROM robot_player_snapshot
        WHERE player_group_id = ?
          AND database_generation = ?
          AND wxid = ?
          AND active = 1
        LIMIT 1
        """.formatted(SNAPSHOT_COLUMNS);

    private static final String PLAYER_BY_WXID_SQL = """
        SELECT s.player_group_id, %s
        FROM robot_player_snapshot s
        INNER JOIN robot_runtime_state r ON r.player_group_id = s.player_group_id
          AND r.database_generation = s.database_generation
        WHERE s.wxid = ?
          AND s.active = 1
        ORDER BY
          (
            SELECT COUNT(*)
            FROM robot_player_snapshot d
            WHERE d.player_group_id = s.player_group_id
              AND d.database_generation = s.database_generation
              AND d.active = 1
              AND d.wxid <> s.wxid
              AND (
                d.direct_parent_wxid = s.wxid
                OR d.parent_path LIKE CONCAT('%%', s.wxid, '###%%')
              )
          ) DESC,
          CASE WHEN s.direct_parent_wxid = s.wxid THEN 0 ELSE 1 END DESC,
          s.source_updated_at DESC,
          s.updated_at DESC
        LIMIT 1
        """.formatted(SNAPSHOT_COLUMNS);

    private static final String HAS_DESCENDANTS_SQL = """
        SELECT COUNT(*) > 0
        FROM robot_player_snapshot
        WHERE player_group_id = ?
          AND database_generation = ?
          AND active = 1
          AND wxid <> ?
          AND (direct_parent_wxid = ? OR parent_path LIKE CONCAT('%%', ?, '###%%'))
        """;

    private static final String DIRECT_CHILDREN_SQL = """
        SELECT %s
        FROM robot_player_snapshot
        WHERE player_group_id = ?
          AND database_generation = ?
          AND active = 1
          AND player_type <> '1'
          AND direct_parent_wxid = ?
        """.formatted(SNAPSHOT_COLUMNS);

    private static final String DAILY_SUMMARY_SQL = """
        SELECT business_timestamp, wxid, player_no, display_name, player_type, direct_parent_wxid,
               balance, total_flow, total_up, total_down, total_profit_loss,
               total_rebate, pending_rebate, rebate_rate,
               agent_pending_flow, agent_pending_rebate
        FROM robot_player_daily_summary
        WHERE player_group_id = ?
          AND business_timestamp >= ?
          AND business_timestamp < ?
          AND player_type <> '1'
          AND (
            wxid = ?
            OR direct_parent_wxid = ?
            OR parent_path LIKE CONCAT('%%', ?, '###%%')
          )
        ORDER BY business_timestamp ASC, wxid ASC
        """;

    private final JdbcTemplate jdbc;
    private final RobotRuntimeStateRepository runtimeStateRepository;

    public AgentPlayerRepository(
            @Qualifier("robotJdbc") JdbcTemplate jdbc,
            RobotRuntimeStateRepository runtimeStateRepository) {
        this.jdbc = jdbc;
        this.runtimeStateRepository = runtimeStateRepository;
    }

    public Optional<AgentPlayerRow> findPlayer(String playerGroupId, String wxid) {
        String generation = currentGeneration(playerGroupId);
        return jdbc.query(PLAYER_SQL, SNAPSHOT_MAPPER, playerGroupId, generation, wxid).stream().findFirst();
    }

    public Optional<AgentPlayerContext> findPlayerContextByWxid(String wxid) {
        return jdbc.query(PLAYER_BY_WXID_SQL, CONTEXT_MAPPER, wxid).stream().findFirst();
    }

    public boolean hasDescendants(String playerGroupId, String wxid) {
        Boolean result = jdbc.queryForObject(
            HAS_DESCENDANTS_SQL,
            Boolean.class,
            playerGroupId,
            currentGeneration(playerGroupId),
            wxid,
            wxid,
            wxid);
        return Boolean.TRUE.equals(result);
    }

    public List<AgentPlayerRow> findVisibleSnapshots(String playerGroupId, String agentWxid) {
        String generation = currentGeneration(playerGroupId);
        return jdbc.query(
            VISIBLE_SNAPSHOT_SQL, SNAPSHOT_MAPPER, playerGroupId, generation, agentWxid, agentWxid, agentWxid);
    }

    public List<AgentPlayerRow> findDirectChildren(String playerGroupId, String agentWxid) {
        return jdbc.query(
            DIRECT_CHILDREN_SQL, SNAPSHOT_MAPPER, playerGroupId, currentGeneration(playerGroupId), agentWxid);
    }

    public List<AgentDailySummaryRow> findVisibleDailySummaries(
            String playerGroupId, String agentWxid, LocalDate startDate, LocalDate endDate) {
        return jdbc.query(
            DAILY_SUMMARY_SQL,
            DAILY_MAPPER,
            playerGroupId,
            RobotSyncSupport.businessDayStartEpoch(startDate),
            RobotSyncSupport.businessDayStartEpoch(endDate.plusDays(1)),
            agentWxid,
            agentWxid,
            agentWxid);
    }

    public boolean isDescendant(String playerGroupId, String agentWxid, String targetWxid) {
        if (agentWxid.equals(targetWxid)) {
            return false;
        }
        Boolean result = jdbc.queryForObject(
            """
            SELECT COUNT(*) > 0
            FROM robot_player_snapshot
            WHERE player_group_id = ?
              AND database_generation = ?
              AND wxid = ?
              AND active = 1
              AND (
                direct_parent_wxid = ?
                OR parent_path LIKE CONCAT('%%', ?, '###%%')
              )
            """,
            Boolean.class,
            playerGroupId,
            currentGeneration(playerGroupId),
            targetWxid,
            agentWxid,
            agentWxid);
        return Boolean.TRUE.equals(result);
    }

    public int countDescendants(String playerGroupId, String agentWxid) {
        Integer count = jdbc.queryForObject(
            """
            SELECT COUNT(*)
            FROM robot_player_snapshot
            WHERE player_group_id = ?
              AND database_generation = ?
              AND active = 1
              AND player_type <> '1'
              AND wxid <> ?
              AND (
                direct_parent_wxid = ?
                OR parent_path LIKE CONCAT('%%', ?, '###%%')
              )
            """,
            Integer.class,
            playerGroupId,
            currentGeneration(playerGroupId),
            agentWxid,
            agentWxid,
            agentWxid);
        return count == null ? 0 : count;
    }

    private String currentGeneration(String playerGroupId) {
        return runtimeStateRepository.requireDatabaseGeneration(playerGroupId);
    }

    private static final RowMapper<AgentPlayerRow> SNAPSHOT_MAPPER = (rs, rowNum) -> mapSnapshot(rs);

    private static final RowMapper<AgentPlayerContext> CONTEXT_MAPPER = (rs, rowNum) ->
        new AgentPlayerContext(rs.getString("player_group_id"), mapSnapshot(rs));

    private static final RowMapper<AgentDailySummaryRow> DAILY_MAPPER = (rs, rowNum) -> new AgentDailySummaryRow(
        RobotSyncSupport.resolveBusinessDate(rs.getLong("business_timestamp"), "+08:00"),
        rs.getString("wxid"),
        rs.getString("player_no"),
        rs.getString("display_name"),
        rs.getString("player_type"),
        rs.getString("direct_parent_wxid"),
        rs.getBigDecimal("balance"),
        rs.getBigDecimal("total_flow"),
        rs.getBigDecimal("total_up"),
        rs.getBigDecimal("total_down"),
        rs.getBigDecimal("total_profit_loss"),
        rs.getBigDecimal("total_rebate"),
        rs.getBigDecimal("pending_rebate"),
        rs.getBigDecimal("rebate_rate"),
        rs.getBigDecimal("agent_pending_flow"),
        rs.getBigDecimal("agent_pending_rebate"));

    private static AgentPlayerRow mapSnapshot(ResultSet rs) throws SQLException {
        return new AgentPlayerRow(
            rs.getString("wxid"),
            rs.getString("player_no"),
            rs.getString("nickname"),
            rs.getString("display_name"),
            rs.getString("remark"),
            rs.getString("player_type"),
            rs.getBigDecimal("balance"),
            rs.getString("direct_parent_wxid"),
            rs.getString("direct_parent_no"),
            rs.getString("parent_path"),
            rs.getInt("level_no"),
            rs.getBigDecimal("total_flow"),
            rs.getBigDecimal("used_flow"),
            rs.getBigDecimal("remaining_flow"),
            rs.getBigDecimal("agent_pending_flow"),
            rs.getBigDecimal("agent_pending_rebate"),
            rs.getBigDecimal("total_up"),
            rs.getBigDecimal("total_down"),
            rs.getBigDecimal("total_profit_loss"),
            rs.getBigDecimal("rebate_rate"),
            rs.getString("rebate_rate_unit"),
            rs.getBigDecimal("total_rebate"),
            rs.getLong("source_updated_at"));
    }
}
