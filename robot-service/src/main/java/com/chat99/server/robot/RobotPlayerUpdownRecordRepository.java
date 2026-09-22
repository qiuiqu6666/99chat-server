package com.chat99.server.robot;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class RobotPlayerUpdownRecordRepository {

    private static final String INSERT_SQL = """
        INSERT INTO robot_player_updown_record (
          event_id, player_group_id, statistics_group_id, database_generation,
          record_id, wxid, player_no, nickname, direction,
          amount, balance_delta, balance_after, total_up_after, total_down_after,
          approved_at, status, approval_source,
          business_timestamp, business_timezone, source_updated_at, sync_reason
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;

    public RobotPlayerUpdownRecordRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int insert(RobotSyncRequest request, PlayerUpdownRecordData data) {
        String generation = request.getDatabaseGeneration();
        if (generation != null && generation.isBlank()) {
            generation = null;
        } else if (generation != null) {
            generation = generation.trim();
        }
        return jdbc.update(INSERT_SQL,
            request.getEventId(),
            request.getPlayerGroupId(),
            request.getStatisticsGroupId(),
            generation,
            data.getRecordId().trim(),
            data.getWxid().trim(),
            data.getPlayerNo(),
            data.getNickname(),
            data.getDirection(),
            data.getAmount(),
            data.getBalanceDelta(),
            data.getBalanceAfter(),
            data.getTotalUpAfter(),
            data.getTotalDownAfter(),
            data.getApprovedAt(),
            blankToNull(data.getStatus()),
            blankToNull(data.getApprovalSource()),
            request.getBusinessTimestamp(),
            request.getBusinessTimezone(),
            request.getSourceUpdatedAt(),
            request.getSyncReason());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
