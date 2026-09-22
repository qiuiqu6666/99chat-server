package com.chat99.server.robot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class RobotGroupBindingRepository {

    private static final RowMapper<RobotGroupBinding> MAPPER = RobotGroupBindingRepository::mapRow;

    private final JdbcTemplate jdbc;

    public RobotGroupBindingRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RobotGroupBinding> findByGroupId(String imGroupId) {
        return jdbc.query(
            """
            SELECT im_group_id, machine_code, robot_id, enabled, bound_at, updated_at
            FROM robot_group_binding
            WHERE im_group_id = ?
            """,
            MAPPER,
            imGroupId).stream().findFirst();
    }

    public Optional<RobotGroupBinding> findByMachineCode(String machineCode) {
        return jdbc.query(
            """
            SELECT im_group_id, machine_code, robot_id, enabled, bound_at, updated_at
            FROM robot_group_binding
            WHERE machine_code = ?
            """,
            MAPPER,
            machineCode).stream().findFirst();
    }

    public void upsertBind(String imGroupId, String machineCode) {
        jdbc.update(
            """
            INSERT INTO robot_group_binding (im_group_id, machine_code, robot_id, enabled, bound_at, updated_at)
            VALUES (?, ?, NULL, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
              robot_id = IF(machine_code = VALUES(machine_code), robot_id, NULL),
              enabled = IF(machine_code = VALUES(machine_code), enabled, 0),
              bound_at = IF(machine_code = VALUES(machine_code), bound_at, NOW()),
              machine_code = VALUES(machine_code),
              updated_at = NOW()
            """,
            imGroupId,
            machineCode);
    }

    public boolean enable(String imGroupId, String machineCode, String robotId) {
        int updated = jdbc.update(
            """
            UPDATE robot_group_binding
            SET robot_id = ?, enabled = 1, updated_at = NOW()
            WHERE im_group_id = ? AND machine_code = ?
            """,
            robotId,
            imGroupId,
            machineCode);
        return updated > 0;
    }

    public int deleteByMachineCode(String machineCode) {
        return jdbc.update(
            "DELETE FROM robot_group_binding WHERE machine_code = ?",
            machineCode);
    }

    private static RobotGroupBinding mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RobotGroupBinding(
            rs.getString("im_group_id"),
            rs.getString("machine_code"),
            rs.getString("robot_id"),
            rs.getBoolean("enabled"),
            toLocalDateTime(rs.getTimestamp("bound_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
