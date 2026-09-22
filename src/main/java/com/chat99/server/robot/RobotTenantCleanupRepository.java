package com.chat99.server.robot;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Cascade cleanup for a machine-code tenant ({@code player_group_id = machine_code}).
 */
@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class RobotTenantCleanupRepository {

    private final JdbcTemplate jdbc;

    public RobotTenantCleanupRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int deleteUpdownRecords(String playerGroupId) {
        return jdbc.update(
            "DELETE FROM robot_player_updown_record WHERE player_group_id = ?",
            playerGroupId);
    }

    public int deleteDailySummaries(String playerGroupId) {
        return jdbc.update(
            "DELETE FROM robot_player_daily_summary WHERE player_group_id = ?",
            playerGroupId);
    }

    public int deleteSnapshots(String playerGroupId) {
        return jdbc.update(
            "DELETE FROM robot_player_snapshot WHERE player_group_id = ?",
            playerGroupId);
    }

    public int deleteSyncEvents(String playerGroupId) {
        return jdbc.update(
            "DELETE FROM robot_sync_event WHERE player_group_id = ?",
            playerGroupId);
    }

    public int deleteRuntimeStates(String playerGroupId) {
        return jdbc.update(
            "DELETE FROM robot_runtime_state WHERE player_group_id = ?",
            playerGroupId);
    }

    public int deleteExportTasks(String playerGroupId) {
        return jdbc.update(
            "DELETE FROM agent_report_export_task WHERE player_group_id = ?",
            playerGroupId);
    }
}
