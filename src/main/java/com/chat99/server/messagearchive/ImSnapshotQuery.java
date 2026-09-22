package com.chat99.server.messagearchive;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ImSnapshotQuery {

    private static final Logger log = LoggerFactory.getLogger(ImSnapshotQuery.class);
    private static final int GROUP_IN_BATCH = 200;

    public record Candidate(String chatType, String peerId, long lastMs, Long lastSeq) {}

    private final JdbcTemplate jdbc;

    public ImSnapshotQuery(@Qualifier("archiveReadJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> listC2cCandidates(List<String> tables, String userId, long sinceMs, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0 || tables == null || tables.isEmpty()) {
            return List.of();
        }
        Map<String, Candidate> merged = new HashMap<>();
        for (String table : tables) {
            String sql = """
                SELECT peer_id, MAX(msg_time_ms) AS last_ms, MAX(msg_seq) AS last_seq
                FROM (
                  SELECT CASE WHEN from_account = ? THEN peer_account ELSE from_account END AS peer_id,
                         msg_time_ms,
                         msg_seq
                  FROM %s
                  WHERE chat_type = 0
                    AND msg_time_ms >= ?
                    AND (from_account = ? OR peer_account = ?)
                    AND peer_account IS NOT NULL
                    AND from_account <> ''
                ) t
                WHERE peer_id IS NOT NULL AND peer_id <> '' AND peer_id <> ?
                GROUP BY peer_id
                ORDER BY last_ms DESC
                LIMIT ?
                """.formatted(table);
            try {
                List<Candidate> rows = jdbc.query(sql, (rs, i) -> new Candidate(
                        "c2c",
                        rs.getString("peer_id"),
                        rs.getLong("last_ms"),
                        rs.getObject("last_seq") == null ? null : rs.getLong("last_seq")),
                    userId, sinceMs, userId, userId, userId, limit);
                for (Candidate row : rows) {
                    merge(merged, row);
                }
            } catch (Exception e) {
                log.warn("snapshot c2c candidate query failed table={} err={}", table, e.toString());
            }
        }
        return top(merged, limit);
    }

    public List<Candidate> listGroupCandidates(
        List<String> tables, List<String> groupIds, long sinceMs, int limit) {
        if (groupIds == null || groupIds.isEmpty() || limit <= 0 || tables == null || tables.isEmpty()) {
            return List.of();
        }
        Map<String, Candidate> merged = new HashMap<>();
        for (int offset = 0; offset < groupIds.size(); offset += GROUP_IN_BATCH) {
            List<String> batch = groupIds.subList(offset, Math.min(offset + GROUP_IN_BATCH, groupIds.size()));
            for (String table : tables) {
                String placeholders = String.join(",", batch.stream().map(g -> "?").toList());
                String sql = """
                    SELECT group_id AS peer_id, MAX(msg_time_ms) AS last_ms, MAX(msg_seq) AS last_seq
                    FROM %s
                    WHERE chat_type = 1
                      AND msg_time_ms >= ?
                      AND group_id IN (%s)
                    GROUP BY group_id
                    ORDER BY last_ms DESC
                    LIMIT ?
                    """.formatted(table, placeholders);
                try {
                    Object[] args = new Object[batch.size() + 2];
                    args[0] = sinceMs;
                    for (int i = 0; i < batch.size(); i++) {
                        args[i + 1] = batch.get(i);
                    }
                    args[args.length - 1] = limit;
                    List<Candidate> rows = jdbc.query(sql, (rs, i) -> new Candidate(
                            "group",
                            rs.getString("peer_id"),
                            rs.getLong("last_ms"),
                            rs.getObject("last_seq") == null ? null : rs.getLong("last_seq")),
                        args);
                    for (Candidate row : rows) {
                        merge(merged, row);
                    }
                } catch (Exception e) {
                    log.warn("snapshot group candidate query failed table={} err={}", table, e.toString());
                }
            }
        }
        return top(merged, limit);
    }

    private static void merge(Map<String, Candidate> merged, Candidate row) {
        if (row == null || row.peerId() == null || row.peerId().isBlank()) {
            return;
        }
        String key = row.chatType() + '\0' + row.peerId();
        Candidate existing = merged.get(key);
        if (existing == null || row.lastMs() > existing.lastMs()) {
            merged.put(key, row);
        } else if (row.lastMs() == existing.lastMs()) {
            Long seq = existing.lastSeq();
            if (seq == null && row.lastSeq() != null) {
                merged.put(key, row);
            } else if (seq != null && row.lastSeq() != null && row.lastSeq() > seq) {
                merged.put(key, row);
            }
        }
    }

    private static List<Candidate> top(Map<String, Candidate> merged, int limit) {
        List<Candidate> list = new ArrayList<>(merged.values());
        list.sort(Comparator.comparingLong(Candidate::lastMs).reversed());
        if (list.size() > limit) {
            return List.copyOf(list.subList(0, limit));
        }
        return List.copyOf(list);
    }
}
