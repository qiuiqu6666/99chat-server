package com.chat99.server.messagearchive;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ChatMessageWriteRepository {

    private static final String INSERT_SQL = """
        INSERT IGNORE INTO %s (
          msg_key, msg_id, chat_type, from_account, peer_account, group_id, msg_seq,
          msg_time_ms, elem_type, preview_text, msg_body_json, status, callback_cmd
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String REVOKE_SQL = """
        UPDATE %s
        SET status = 0, preview_text = ?
        WHERE msg_key = ? AND status = 1
        """;

    private final JdbcTemplate jdbc;

    public ChatMessageWriteRepository(@Qualifier("archiveWriteJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int batchInsertIgnore(String table, List<ImMessageArchiveEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        ensureMsgIdColumn(table);
        String sql = INSERT_SQL.formatted(table);
        int[][] counts = jdbc.batchUpdate(sql, events, events.size(), (ps, event) -> {
            ps.setString(1, event.msgKey());
            ps.setString(2, event.msgId());
            ps.setInt(3, ImMessageArchiveParser.CHAT_TYPE_GROUP.equals(event.chatType()) ? 1 : 0);
            ps.setString(4, event.fromAccount());
            ps.setString(5, event.peerAccount());
            ps.setString(6, event.groupId());
            if (event.msgSeq() != null) {
                ps.setLong(7, event.msgSeq());
            } else {
                ps.setObject(7, null);
            }
            ps.setLong(8, event.msgTimeMs());
            ps.setString(9, event.elemType());
            ps.setString(10, event.previewText());
            ps.setString(11, event.msgBodyJson());
            ps.setInt(12, 1);
            ps.setString(13, event.callbackCommand());
        });
        int inserted = 0;
        for (int[] row : counts) {
            for (int c : row) {
                if (c >= 0) {
                    inserted += c;
                }
            }
        }
        return inserted;
    }

    public int batchMarkRevoked(String table, List<String> msgKeys) {
        if (msgKeys == null || msgKeys.isEmpty()) {
            return 0;
        }
        String sql = REVOKE_SQL.formatted(table);
        try {
            int[][] counts = jdbc.batchUpdate(sql, msgKeys, msgKeys.size(), (ps, msgKey) -> {
                ps.setString(1, ImMessageRecallParser.REVOKED_PREVIEW_TEXT);
                ps.setString(2, msgKey);
            });
            int updated = 0;
            for (int[] row : counts) {
                for (int c : row) {
                    if (c > 0) {
                        updated += c;
                    }
                }
            }
            return updated;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public void ensureTable(String table, String suffix) {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS %s (
              id              BIGINT       NOT NULL AUTO_INCREMENT,
              msg_key         VARCHAR(128) NOT NULL,
              msg_id          VARCHAR(192) NULL,
              chat_type       TINYINT      NOT NULL,
              from_account    VARCHAR(32)  NOT NULL,
              peer_account    VARCHAR(32)  NULL,
              group_id        VARCHAR(32)  NULL,
              msg_seq         BIGINT       NULL,
              msg_time_ms     BIGINT       NOT NULL,
              elem_type       VARCHAR(32)  NULL,
              preview_text    VARCHAR(512) NULL,
              msg_body_json   JSON         NOT NULL,
              status          TINYINT      NOT NULL DEFAULT 1,
              callback_cmd    VARCHAR(64)  NOT NULL,
              created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
              PRIMARY KEY (id),
              UNIQUE KEY uk_msg_key (msg_key),
              KEY idx_msg_id (msg_id),
              KEY idx_c2c_peer_time (chat_type, peer_account, from_account, msg_time_ms),
              KEY idx_group_time (chat_type, group_id, msg_time_ms),
              KEY idx_group_seq (chat_type, group_id, msg_seq),
              KEY idx_msg_time (msg_time_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """.formatted(table));
        ensureMsgIdColumn(table);
        ensureMsgTimeIndex(table);
        jdbc.update(
            "INSERT IGNORE INTO chat_message_table_registry (table_suffix, physical_table) VALUES (?, ?)",
            suffix, table);
    }

    /** 老月表幂等补列。 */
    public void ensureMsgIdColumn(String table) {
        if (table == null || table.isBlank()) {
            return;
        }
        Integer exists = jdbc.query(
            """
            SELECT COUNT(1) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'msg_id'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            table);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN msg_id VARCHAR(192) NULL AFTER msg_key");
        try {
            jdbc.execute("ALTER TABLE `" + table + "` ADD KEY idx_msg_id (msg_id)");
        } catch (Exception ignored) {
            // index may already exist under another name
        }
    }

    /**
     * 老月表幂等补 msg_time_ms 单列索引：av_call 增量合并等按时间范围扫描依赖它，
     * 缺失时会退化为 100 万行级全表扫描。
     */
    public void ensureMsgTimeIndex(String table) {
        if (table == null || !table.matches("chat_message_\\d{6}")) {
            return;
        }
        Integer exists = jdbc.query(
            """
            SELECT COUNT(1) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = 'idx_msg_time'
            """,
            rs -> rs.next() ? rs.getInt(1) : 0,
            table);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.execute("ALTER TABLE `" + table + "` ADD INDEX idx_msg_time (msg_time_ms)");
    }

    public int updateMsgIdIfNull(String table, String msgKey, String msgId) {
        if (table == null || msgKey == null || msgId == null || msgId.isBlank()) {
            return 0;
        }
        ensureMsgIdColumn(table);
        return jdbc.update(
            "UPDATE `" + table + "` SET msg_id = ? WHERE msg_key = ? AND (msg_id IS NULL OR msg_id = '')",
            msgId, msgKey);
    }

    public void insertFailLog(String msgKey, String payloadJson, String errorMessage, int retryCount) {
        jdbc.update(
            "INSERT INTO chat_message_archive_fail_log (msg_key, payload_json, error_message, retry_count) VALUES (?, ?, ?, ?)",
            msgKey, payloadJson, errorMessage, retryCount);
    }
}
