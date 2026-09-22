package com.chat99.server.messagearchive;

import com.chat99.server.messagearchive.ImSnapshotQuery.Candidate;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 最近会话投影（主库）。C2C 按用户维；群按群维单行，禁止成员 fan-out。
 */
@Repository
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ConversationRecentRepository {

    private static final Logger log = LoggerFactory.getLogger(ConversationRecentRepository.class);

    private static final String UPSERT_C2C = """
        INSERT INTO user_c2c_conversation_recent (
          user_id, peer_id, last_msg_time_ms, last_msg_key, last_seq,
          last_sender, last_elem_type, last_preview
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          last_msg_time_ms = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_msg_time_ms), last_msg_time_ms),
          last_msg_key = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_msg_key), last_msg_key),
          last_seq = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_seq), last_seq),
          last_sender = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_sender), last_sender),
          last_elem_type = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_elem_type), last_elem_type),
          last_preview = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_preview), last_preview)
        """;

    private static final String UPSERT_GROUP = """
        INSERT INTO group_conversation_recent (
          group_id, last_msg_time_ms, last_msg_key, last_seq,
          last_sender, last_elem_type, last_preview
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          last_msg_time_ms = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_msg_time_ms), last_msg_time_ms),
          last_msg_key = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_msg_key), last_msg_key),
          last_seq = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_seq), last_seq),
          last_sender = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_sender), last_sender),
          last_elem_type = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_elem_type), last_elem_type),
          last_preview = IF(VALUES(last_msg_time_ms) >= last_msg_time_ms, VALUES(last_preview), last_preview)
        """;

    private final JdbcTemplate jdbc;

    public ConversationRecentRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void upsertC2c(String userId, String peerId, long lastMsgTimeMs, String lastMsgKey,
                          Long lastSeq, String lastSender, String lastElemType, String lastPreview) {
        if (blank(userId) || blank(peerId) || userId.equals(peerId)) {
            return;
        }
        jdbc.update(UPSERT_C2C,
            userId, peerId, lastMsgTimeMs, lastMsgKey, lastSeq,
            lastSender, lastElemType, truncate(lastPreview, 512));
    }

    public void upsertGroup(String groupId, long lastMsgTimeMs, String lastMsgKey,
                            Long lastSeq, String lastSender, String lastElemType, String lastPreview) {
        if (blank(groupId)) {
            return;
        }
        jdbc.update(UPSERT_GROUP,
            groupId, lastMsgTimeMs, lastMsgKey, lastSeq,
            lastSender, lastElemType, truncate(lastPreview, 512));
    }

    public List<Candidate> listC2cCandidates(String userId, long sinceMs, int limit) {
        if (blank(userId) || limit <= 0) {
            return List.of();
        }
        try {
            return jdbc.query("""
                SELECT peer_id, last_msg_time_ms, last_seq
                FROM user_c2c_conversation_recent
                WHERE user_id = ?
                  AND last_msg_time_ms >= ?
                ORDER BY last_msg_time_ms DESC
                LIMIT ?
                """,
                (rs, i) -> new Candidate(
                    "c2c",
                    rs.getString("peer_id"),
                    rs.getLong("last_msg_time_ms"),
                    rs.getObject("last_seq") == null ? null : rs.getLong("last_seq")),
                userId, sinceMs, limit);
        } catch (Exception e) {
            log.warn("listC2cCandidates failed err={}", e.toString());
            return List.of();
        }
    }

    public List<Candidate> listGroupCandidates(String userId, long sinceMs, int limit) {
        if (blank(userId) || limit <= 0) {
            return List.of();
        }
        try {
            return jdbc.query("""
                SELECT r.group_id AS peer_id, r.last_msg_time_ms, r.last_seq
                FROM group_member m
                INNER JOIN group_conversation_recent r ON r.group_id = m.group_id
                INNER JOIN group_profile p ON p.group_id = m.group_id AND p.dismissed = 0
                WHERE m.user_id = ?
                  AND r.last_msg_time_ms >= ?
                ORDER BY r.last_msg_time_ms DESC
                LIMIT ?
                """,
                (rs, i) -> new Candidate(
                    "group",
                    rs.getString("peer_id"),
                    rs.getLong("last_msg_time_ms"),
                    rs.getObject("last_seq") == null ? null : rs.getLong("last_seq")),
                userId, sinceMs, limit);
        } catch (Exception e) {
            log.warn("listGroupCandidates failed err={}", e.toString());
            return List.of();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
