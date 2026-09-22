package com.chat99.server.robot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class RobotRuntimeStateRepository {

    private static final RowMapper<RobotRuntimeState> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public RobotRuntimeStateRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RobotRuntimeState> findByRobotId(String robotId) {
        return jdbc.query(
            """
            SELECT player_group_id, database_generation, sync_status, player_count,
                   sync_started_at, sync_completed_at, updated_at
            FROM robot_runtime_state
            WHERE player_group_id = ?
            """,
            MAPPER,
            robotId).stream().findFirst();
    }

    public String requireDatabaseGeneration(String playerGroupId) {
        return findDatabaseGeneration(playerGroupId)
            .orElseGet(() -> initializeReady(playerGroupId, "gen-1"));
    }

    public Optional<String> findDatabaseGeneration(String playerGroupId) {
        return findByRobotId(playerGroupId).map(RobotRuntimeState::databaseGeneration);
    }

    public void upsertDatabaseGeneration(String playerGroupId, String databaseGeneration) {
        jdbc.update(
            """
            INSERT INTO robot_runtime_state (player_group_id, database_generation, sync_status, player_count)
            VALUES (?, ?, 'READY', 0)
            ON DUPLICATE KEY UPDATE database_generation = VALUES(database_generation)
            """,
            playerGroupId,
            databaseGeneration);
    }

    public void markSyncing(String robotId, String databaseGeneration, long businessTimestamp) {
        LocalDateTime startedAt = LocalDateTime.ofInstant(
            Instant.ofEpochSecond(businessTimestamp), java.time.ZoneOffset.UTC);
        jdbc.update(
            """
            INSERT INTO robot_runtime_state (
              player_group_id, database_generation, sync_status, player_count,
              sync_started_at, sync_completed_at
            ) VALUES (?, ?, 'SYNCING', 0, ?, NULL)
            ON DUPLICATE KEY UPDATE
              database_generation = VALUES(database_generation),
              sync_status = 'SYNCING',
              player_count = 0,
              sync_started_at = VALUES(sync_started_at),
              sync_completed_at = NULL
            """,
            robotId,
            databaseGeneration,
            Timestamp.valueOf(startedAt));
    }

    public boolean markReady(String robotId, String databaseGeneration, int playerCount, long businessTimestamp) {
        LocalDateTime completedAt = LocalDateTime.ofInstant(
            Instant.ofEpochSecond(businessTimestamp), java.time.ZoneOffset.UTC);
        int updated = jdbc.update(
            """
            UPDATE robot_runtime_state SET
              sync_status = 'READY',
              player_count = ?,
              sync_completed_at = ?
            WHERE player_group_id = ?
              AND database_generation = ?
              AND sync_status = 'SYNCING'
            """,
            playerCount,
            Timestamp.valueOf(completedAt),
            robotId,
            databaseGeneration);
        return updated > 0;
    }

    private String initializeReady(String playerGroupId, String databaseGeneration) {
        jdbc.update(
            """
            INSERT INTO robot_runtime_state (player_group_id, database_generation, sync_status, player_count)
            VALUES (?, ?, 'READY', 0)
            ON DUPLICATE KEY UPDATE database_generation = database_generation
            """,
            playerGroupId,
            databaseGeneration);
        return findDatabaseGeneration(playerGroupId).orElse(databaseGeneration);
    }

    private static RobotRuntimeState map(ResultSet rs) throws SQLException {
        Timestamp started = rs.getTimestamp("sync_started_at");
        Timestamp completed = rs.getTimestamp("sync_completed_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        String status = rs.getString("sync_status");
        if (status == null || status.isBlank()) {
            status = RobotRuntimeState.STATUS_READY;
        }
        return new RobotRuntimeState(
            rs.getString("player_group_id"),
            rs.getString("database_generation"),
            status,
            rs.getInt("player_count"),
            started == null ? null : started.toLocalDateTime(),
            completed == null ? null : completed.toLocalDateTime(),
            updated == null ? null : updated.toLocalDateTime());
    }
}
