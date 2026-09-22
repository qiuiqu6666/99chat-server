package com.chat99.server.robot;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class RobotRebateRequestRepository {

    private static final String REBATE_COLUMNS = """
        wxid, player_no, nickname, display_name, player_type, balance,
        total_flow, used_flow, remaining_flow, rebate_rate, total_rebate,
        rebate_request_id, rebate_request_type, rebate_request_status, rebate_lease_token,
        rebate_requested_at, rebate_flow_to_consume, rebate_amount,
        rebate_expected_balance, rebate_expected_total_flow, rebate_expected_used_flow,
        rebate_database_generation, rebate_result_flow, rebate_result_amount,
        rebate_result_code, rebate_result_message, rebate_retryable
        """;

    private static final RowMapper<RobotRebateRequestState> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public RobotRebateRequestRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RobotRebateRequestState> findByWxid(String playerGroupId, String databaseGeneration, String wxid) {
        return jdbc.query(
            """
            SELECT player_group_id, %s
            FROM robot_player_snapshot
            WHERE player_group_id = ? AND database_generation = ? AND wxid = ?
            """.formatted(REBATE_COLUMNS),
            MAPPER,
            playerGroupId,
            databaseGeneration,
            wxid).stream().findFirst();
    }

    public Optional<RobotRebateRequestState> findActiveByWxid(
            String playerGroupId, String databaseGeneration, String wxid) {
        return jdbc.query(
            """
            SELECT player_group_id, %s
            FROM robot_player_snapshot
            WHERE player_group_id = ? AND database_generation = ? AND wxid = ?
              AND rebate_request_status IN ('PENDING', 'PROCESSING')
            """.formatted(REBATE_COLUMNS),
            MAPPER,
            playerGroupId,
            databaseGeneration,
            wxid).stream().findFirst();
    }

    public Optional<RobotRebateRequestState> findByRequestId(String playerGroupId, String requestId) {
        return jdbc.query(
            "SELECT player_group_id, " + REBATE_COLUMNS + " FROM robot_player_snapshot WHERE player_group_id = ? AND rebate_request_id = ?",
            MAPPER,
            playerGroupId,
            requestId).stream().findFirst();
    }

    public List<RobotRebateRequestState> findPullable(String playerGroupId, String databaseGeneration, int limit) {
        return jdbc.query(
            """
            SELECT player_group_id, %s
            FROM robot_player_snapshot
            WHERE player_group_id = ?
              AND database_generation = ?
              AND rebate_request_status IN ('PENDING', 'PROCESSING')
              AND rebate_database_generation = ?
              AND player_type <> '1'
            ORDER BY rebate_requested_at ASC, wxid ASC
            LIMIT ?
            """.formatted(REBATE_COLUMNS),
            MAPPER,
            playerGroupId,
            databaseGeneration,
            databaseGeneration,
            limit);
    }

    /** 不限定 robotId：拉取所有 READY 机器人当前代次上的待处理反水任务（只靠密钥鉴权时用）。 */
    public List<RobotRebateRequestState> findPullableAnyReady(String databaseGenerationOrNull, int limit) {
        if (databaseGenerationOrNull != null && !databaseGenerationOrNull.isBlank()) {
            String generation = databaseGenerationOrNull.trim();
            return jdbc.query(
                """
                SELECT s.player_group_id, %s
                FROM robot_player_snapshot s
                INNER JOIN robot_runtime_state r
                  ON r.player_group_id = s.player_group_id
                 AND r.database_generation = s.database_generation
                 AND r.sync_status = 'READY'
                WHERE s.database_generation = ?
                  AND s.rebate_request_status IN ('PENDING', 'PROCESSING')
                  AND s.rebate_database_generation = ?
                  AND s.player_type <> '1'
                ORDER BY s.rebate_requested_at ASC, s.wxid ASC
                LIMIT ?
                """.formatted(REBATE_COLUMNS),
                MAPPER,
                generation,
                generation,
                limit);
        }
        return jdbc.query(
            """
            SELECT s.player_group_id, %s
            FROM robot_player_snapshot s
            INNER JOIN robot_runtime_state r
              ON r.player_group_id = s.player_group_id
             AND r.database_generation = s.database_generation
             AND r.sync_status = 'READY'
            WHERE s.rebate_request_status IN ('PENDING', 'PROCESSING')
              AND s.rebate_database_generation = s.database_generation
              AND s.player_type <> '1'
            ORDER BY s.rebate_requested_at ASC, s.wxid ASC
            LIMIT ?
            """.formatted(REBATE_COLUMNS),
            MAPPER,
            limit);
    }

    public boolean markProcessing(String playerGroupId, String wxid, String requestId) {
        int updated = jdbc.update(
            """
            UPDATE robot_player_snapshot
            SET rebate_request_status = 'PROCESSING'
            WHERE player_group_id = ? AND wxid = ? AND rebate_request_id = ?
              AND rebate_request_status = 'PENDING'
            """,
            playerGroupId,
            wxid,
            requestId);
        return updated > 0;
    }

    public void createRequest(RobotRebateRequestDraft draft) {
        jdbc.update(
            """
            UPDATE robot_player_snapshot SET
              rebate_request_id = ?,
              rebate_request_type = ?,
              rebate_request_status = 'PENDING',
              rebate_lease_token = ?,
              rebate_requested_at = ?,
              rebate_flow_to_consume = ?,
              rebate_amount = ?,
              rebate_expected_balance = ?,
              rebate_expected_total_flow = ?,
              rebate_expected_used_flow = ?,
              rebate_database_generation = ?,
              rebate_result_flow = NULL,
              rebate_result_amount = NULL,
              rebate_result_code = NULL,
              rebate_result_message = NULL,
              rebate_retryable = NULL
            WHERE player_group_id = ? AND database_generation = ? AND wxid = ?
            """,
            draft.requestId(),
            draft.settlementType(),
            draft.leaseToken(),
            Timestamp.valueOf(draft.requestedAt()),
            draft.flowToConsume(),
            draft.rebateAmount(),
            draft.expectedBalance(),
            draft.expectedTotalFlow(),
            draft.expectedUsedFlow(),
            draft.databaseGeneration(),
            draft.playerGroupId(),
            draft.databaseGeneration(),
            draft.wxid());
    }

    public boolean markSuccess(
            String playerGroupId,
            String requestId,
            String leaseToken,
            BigDecimal resultFlow,
            BigDecimal resultAmount,
            String resultCode,
            String resultMessage) {
        int updated = jdbc.update(
            """
            UPDATE robot_player_snapshot SET
              rebate_request_status = 'SUCCESS',
              rebate_result_flow = ?,
              rebate_result_amount = ?,
              rebate_result_code = ?,
              rebate_result_message = ?,
              rebate_retryable = 0
            WHERE player_group_id = ? AND rebate_request_id = ? AND rebate_lease_token = ?
              AND rebate_request_status = 'PROCESSING'
            """,
            resultFlow,
            resultAmount,
            resultCode,
            resultMessage,
            playerGroupId,
            requestId,
            leaseToken);
        return updated > 0;
    }

    public int invalidateOldGenerationRequests(String robotId, String currentDatabaseGeneration) {
        return jdbc.update(
            """
            UPDATE robot_player_snapshot SET
              rebate_request_status = 'FAILED',
              rebate_result_code = 'STALE_DATABASE_GENERATION',
              rebate_result_message = 'invalidated by database reset',
              rebate_retryable = 0,
              rebate_lease_token = NULL
            WHERE player_group_id = ?
              AND rebate_request_status IN ('PENDING', 'PROCESSING')
              AND (
                rebate_database_generation IS NULL
                OR rebate_database_generation <> ?
              )
            """,
            robotId,
            currentDatabaseGeneration);
    }

    public boolean markFailed(
            String playerGroupId,
            String requestId,
            String leaseToken,
            boolean retryable,
            String resultCode,
            String resultMessage) {
        String nextStatus = retryable ? "PENDING" : "FAILED";
        int updated = jdbc.update(
            """
            UPDATE robot_player_snapshot SET
              rebate_request_status = ?,
              rebate_result_code = ?,
              rebate_result_message = ?,
              rebate_retryable = ?
            WHERE player_group_id = ? AND rebate_request_id = ? AND rebate_lease_token = ?
              AND rebate_request_status = 'PROCESSING'
            """,
            nextStatus,
            resultCode,
            resultMessage,
            retryable ? 1 : 0,
            playerGroupId,
            requestId,
            leaseToken);
        return updated > 0;
    }

    private static RobotRebateRequestState map(ResultSet rs) throws SQLException {
        Timestamp requestedAt = rs.getTimestamp("rebate_requested_at");
        return new RobotRebateRequestState(
            rs.getString("player_group_id"),
            rs.getString("wxid"),
            rs.getString("player_no"),
            rs.getString("display_name"),
            rs.getString("player_type"),
            rs.getBigDecimal("balance"),
            rs.getBigDecimal("total_flow"),
            rs.getBigDecimal("used_flow"),
            rs.getBigDecimal("remaining_flow"),
            rs.getBigDecimal("rebate_rate"),
            rs.getBigDecimal("total_rebate"),
            rs.getString("rebate_request_id"),
            rs.getString("rebate_request_type"),
            rs.getString("rebate_request_status"),
            rs.getString("rebate_lease_token"),
            requestedAt == null ? null : requestedAt.toLocalDateTime(),
            rs.getBigDecimal("rebate_flow_to_consume"),
            rs.getBigDecimal("rebate_amount"),
            rs.getBigDecimal("rebate_expected_balance"),
            rs.getBigDecimal("rebate_expected_total_flow"),
            rs.getBigDecimal("rebate_expected_used_flow"),
            rs.getString("rebate_database_generation"),
            rs.getBigDecimal("rebate_result_flow"),
            rs.getBigDecimal("rebate_result_amount"),
            rs.getString("rebate_result_code"),
            rs.getString("rebate_result_message"),
            rs.getObject("rebate_retryable") == null ? null : rs.getInt("rebate_retryable") == 1);
    }

    public record RobotRebateRequestDraft(
        String playerGroupId,
        String wxid,
        String requestId,
        String leaseToken,
        String settlementType,
        LocalDateTime requestedAt,
        BigDecimal flowToConsume,
        BigDecimal rebateAmount,
        BigDecimal expectedBalance,
        BigDecimal expectedTotalFlow,
        BigDecimal expectedUsedFlow,
        String databaseGeneration) {}
}
