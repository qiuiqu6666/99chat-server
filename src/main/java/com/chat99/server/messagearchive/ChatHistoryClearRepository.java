package com.chat99.server.messagearchive;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ChatHistoryClearRepository {

    static final int CHAT_TYPE_C2C = 0;
    static final int CHAT_TYPE_GROUP = 1;

    private final JdbcTemplate readJdbc;
    private final JdbcTemplate writeJdbc;

    public ChatHistoryClearRepository(@Qualifier("archiveReadJdbc") JdbcTemplate readJdbc,
                                      @Qualifier("archiveWriteJdbc") JdbcTemplate writeJdbc) {
        this.readJdbc = readJdbc;
        this.writeJdbc = writeJdbc;
    }

    public long getClearedBeforeMs(String userId, int chatType, String convKey) {
        if (userId == null || convKey == null || userId.isBlank() || convKey.isBlank()) {
            return 0L;
        }
        try {
            Long value = readJdbc.query(
                """
                SELECT cleared_before_ms
                FROM user_chat_history_clear
                WHERE user_id = ? AND chat_type = ? AND conv_key = ?
                """,
                rs -> rs.next() ? rs.getLong("cleared_before_ms") : null,
                userId, chatType, convKey.trim());
            return value == null ? 0L : value;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    public long markCleared(String userId, int chatType, String convKey, long clearedBeforeMs) {
        writeJdbc.update(
            """
            INSERT INTO user_chat_history_clear (user_id, chat_type, conv_key, cleared_before_ms)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE cleared_before_ms = GREATEST(cleared_before_ms, VALUES(cleared_before_ms))
            """,
            userId, chatType, convKey.trim(), clearedBeforeMs);
        return clearedBeforeMs;
    }
}
