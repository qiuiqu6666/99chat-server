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
public class RobotMachineRepository {

    private static final RowMapper<RobotMachine> MAPPER = RobotMachineRepository::mapRow;

    private final JdbcTemplate jdbc;

    public RobotMachineRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<RobotMachine> findByCode(String machineCode) {
        return jdbc.query(
            """
            SELECT machine_code, status, label, created_at, last_seen_at
            FROM robot_machine
            WHERE machine_code = ?
            """,
            MAPPER,
            machineCode).stream().findFirst();
    }

    public void insert(String machineCode, String label) {
        jdbc.update(
            """
            INSERT INTO robot_machine (machine_code, status, label, created_at, last_seen_at)
            VALUES (?, 'ACTIVE', ?, NOW(), NOW())
            """,
            machineCode,
            label);
    }

    public void touchLastSeen(String machineCode) {
        jdbc.update(
            "UPDATE robot_machine SET last_seen_at = NOW() WHERE machine_code = ?",
            machineCode);
    }

    public int deleteByCode(String machineCode) {
        return jdbc.update(
            "DELETE FROM robot_machine WHERE machine_code = ?",
            machineCode);
    }

    private static RobotMachine mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RobotMachine(
            rs.getString("machine_code"),
            rs.getString("status"),
            rs.getString("label"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("last_seen_at")));
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
