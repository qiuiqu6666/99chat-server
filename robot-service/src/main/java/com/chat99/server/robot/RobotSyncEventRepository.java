package com.chat99.server.robot;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ConditionalOnProperty(name = "robot.enabled", havingValue = "true", matchIfMissing = true)
@Repository
public class RobotSyncEventRepository {

    private static final String INSERT_SQL = """
        INSERT INTO robot_sync_event (
          event_id, protocol_version, event_type, player_group_id, statistics_group_id,
          entity_id, business_timestamp, business_timezone, source_updated_at, sync_reason,
          request_body, process_status
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'received')
        """;

    private static final String MARK_PROCESSED_SQL = """
        UPDATE robot_sync_event
        SET process_status = 'processed', processed_at = CURRENT_TIMESTAMP
        WHERE event_id = ?
        """;

    private static final String EXISTS_SQL = """
        SELECT 1 FROM robot_sync_event WHERE event_id = ? LIMIT 1
        """;

    private final JdbcTemplate jdbc;

    public RobotSyncEventRepository(@Qualifier("robotJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByEventId(String eventId) {
        return !jdbc.query(EXISTS_SQL, (rs, rowNum) -> 1, eventId).isEmpty();
    }

    public void insertReceived(RobotSyncRequest request, String requestBodyJson) {
        try {
            jdbc.update(INSERT_SQL,
                request.getEventId(),
                request.getProtocolVersion(),
                request.getEventType(),
                request.getPlayerGroupId(),
                request.getStatisticsGroupId(),
                request.getEntityId(),
                request.getBusinessTimestamp(),
                request.getBusinessTimezone(),
                request.getSourceUpdatedAt(),
                request.getSyncReason(),
                requestBodyJson);
        } catch (DuplicateKeyException e) {
            throw new RobotSyncDuplicateEventException(request.getEventId());
        }
    }

    public void markProcessed(String eventId) {
        jdbc.update(MARK_PROCESSED_SQL, eventId);
    }
}
