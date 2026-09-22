package com.chat99.server.robot.agent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
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
public class AgentReportExportRepository {

    private static final String COLUMNS = """
        id, task_no, requester_user_id, player_group_id, agent_player_no, agent_name,
        start_date, end_date, file_type, include_agent_detail, include_player_detail,
        task_status, progress, file_path, file_name, file_size, content_type, row_count,
        error_message, created_at, started_at, completed_at, expires_at
        """;

    private static final RowMapper<AgentReportExportTask> MAPPER = (rs, rowNum) -> map(rs);

    private final JdbcTemplate jdbc;

    public AgentReportExportRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AgentReportExportTask task) {
        jdbc.update("""
            INSERT INTO agent_report_export_task (
              task_no, requester_user_id, player_group_id, agent_player_no, agent_name,
              start_date, end_date, file_type, include_agent_detail, include_player_detail,
              task_status, progress, row_count, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            task.taskNo(),
            task.requesterUserId(),
            task.playerGroupId(),
            task.agentPlayerNo(),
            task.agentName(),
            task.startDate(),
            task.endDate(),
            task.fileType(),
            task.includeAgentDetail() ? 1 : 0,
            task.includePlayerDetail() ? 1 : 0,
            task.taskStatus(),
            task.progress(),
            task.rowCount(),
            task.expiresAt() == null ? null : Timestamp.valueOf(task.expiresAt()));
    }

    public Optional<AgentReportExportTask> findByTaskNo(String taskNo) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM agent_report_export_task WHERE task_no = ? LIMIT 1",
            MAPPER,
            taskNo).stream().findFirst();
    }

    public Optional<Long> claimNextPendingId() {
        List<Long> ids = jdbc.queryForList(
            """
            SELECT id FROM agent_report_export_task
            WHERE task_status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT 1
            """,
            Long.class);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        long id = ids.get(0);
        int updated = jdbc.update(
            """
            UPDATE agent_report_export_task
            SET task_status = 'RUNNING', started_at = NOW(3), progress = 1
            WHERE id = ? AND task_status = 'PENDING'
            """,
            id);
        return updated == 1 ? Optional.of(id) : Optional.empty();
    }

    public Optional<AgentReportExportTask> findById(long id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM agent_report_export_task WHERE id = ? LIMIT 1",
            MAPPER,
            id).stream().findFirst();
    }

    public void markProgress(String taskNo, int progress) {
        jdbc.update(
            "UPDATE agent_report_export_task SET progress = ? WHERE task_no = ?",
            progress, taskNo);
    }

    public void markCompleted(String taskNo, String filePath, String fileName, long fileSize,
                              String contentType, int rowCount) {
        jdbc.update("""
            UPDATE agent_report_export_task
            SET task_status = 'COMPLETED', progress = 100,
                file_path = ?, file_name = ?, file_size = ?, content_type = ?,
                row_count = ?, completed_at = NOW(3), error_message = NULL
            WHERE task_no = ?
            """,
            filePath, fileName, fileSize, contentType, rowCount, taskNo);
    }

    public void markFailed(String taskNo, String errorMessage) {
        jdbc.update("""
            UPDATE agent_report_export_task
            SET task_status = 'FAILED', progress = 0,
                error_message = ?, completed_at = NOW(3)
            WHERE task_no = ?
            """,
            errorMessage, taskNo);
    }

    public int deleteExpired(LocalDateTime before) {
        return jdbc.update(
            "DELETE FROM agent_report_export_task WHERE expires_at IS NOT NULL AND expires_at < ?",
            Timestamp.valueOf(before));
    }

    private static AgentReportExportTask map(ResultSet rs) throws SQLException {
        return new AgentReportExportTask(
            rs.getLong("id"),
            rs.getString("task_no"),
            rs.getString("requester_user_id"),
            rs.getString("player_group_id"),
            rs.getString("agent_player_no"),
            rs.getString("agent_name"),
            rs.getDate("start_date").toLocalDate(),
            rs.getDate("end_date").toLocalDate(),
            rs.getString("file_type"),
            rs.getInt("include_agent_detail") == 1,
            rs.getInt("include_player_detail") == 1,
            rs.getString("task_status"),
            rs.getInt("progress"),
            rs.getString("file_path"),
            rs.getString("file_name"),
            rs.getLong("file_size") == 0 && rs.wasNull() ? null : rs.getLong("file_size"),
            rs.getString("content_type"),
            rs.getInt("row_count"),
            rs.getString("error_message"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("started_at")),
            toLocalDateTime(rs.getTimestamp("completed_at")),
            toLocalDateTime(rs.getTimestamp("expires_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
