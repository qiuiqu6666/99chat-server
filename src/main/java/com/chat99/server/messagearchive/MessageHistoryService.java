package com.chat99.server.messagearchive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class MessageHistoryService {

    private static final int GROUP_SEQ_MONTHS_BACK = 11;

    public record HistoryItem(
        String msgKey,
        String msgId,
        String fromAccount,
        String peerAccount,
        String groupId,
        Long msgSeq,
        long msgTimeMs,
        String elemType,
        String previewText,
        List<Map<String, Object>> msgBody,
        int status) {}

    public record HistoryPage(List<HistoryItem> items, Long nextCursor, boolean hasMore) {}

    private final JdbcTemplate jdbc;
    private final ChatMessageTableRouter tableRouter;
    private final ChatHistoryClearService clearService;
    private final ObjectMapper json;

    public MessageHistoryService(@Qualifier("archiveReadJdbc") JdbcTemplate jdbc,
                                 ChatMessageTableRouter tableRouter,
                                 ChatHistoryClearService clearService,
                                 ObjectMapper json) {
        this.jdbc = jdbc;
        this.tableRouter = tableRouter;
        this.clearService = clearService;
        this.json = json;
    }

    public HistoryPage listC2c(String userId, String peerUserId, Long cursor,
                               Long fromTimeMs, Long toTimeMs, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        long clearedBeforeMs = clearService.clearedBeforeMsC2c(userId, peerUserId);

        if (fromTimeMs != null || toTimeMs != null) {
            if (fromTimeMs == null || toTimeMs == null) {
                throw new IllegalArgumentException("fromTimeMs and toTimeMs must both be provided");
            }
            if (fromTimeMs > toTimeMs) {
                throw new IllegalArgumentException("fromTimeMs must be <= toTimeMs");
            }
            List<String> tables = tableRouter.physicalTablesBetween(fromTimeMs, toTimeMs);
            List<HistoryItem> merged = fetchAcrossTables(
                tables,
                safeLimit + 1,
                table -> queryC2cByTimeRange(table, userId, peerUserId, fromTimeMs, toTimeMs,
                    clearedBeforeMs, safeLimit + 1),
                Comparator.comparingLong(HistoryItem::msgTimeMs));
            return toPageAsc(merged, safeLimit, HistoryItem::msgTimeMs);
        }

        long cursorMs = cursor == null ? Long.MAX_VALUE : cursor;
        Instant anchor = cursor == null ? Instant.now() : Instant.ofEpochMilli(cursor);
        List<HistoryItem> merged = fetchAcrossMonths(
            anchor,
            safeLimit + 1,
            table -> queryC2c(table, userId, peerUserId, cursorMs, clearedBeforeMs, safeLimit + 1));
        return toPageDesc(merged, safeLimit);
    }

    public HistoryPage listGroup(String userId, String groupId, Long cursor,
                                 Long fromSeq, Long toSeq, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        long clearedBeforeMs = clearService.clearedBeforeMsGroup(userId, groupId);

        if (fromSeq != null || toSeq != null) {
            if (fromSeq == null || toSeq == null) {
                throw new IllegalArgumentException("fromSeq and toSeq must both be provided");
            }
            if (fromSeq > toSeq) {
                throw new IllegalArgumentException("fromSeq must be <= toSeq");
            }
            List<String> tables = tableRouter.physicalTablesAround(Instant.now(), GROUP_SEQ_MONTHS_BACK);
            List<HistoryItem> merged = fetchAcrossTables(
                tables,
                safeLimit + 1,
                table -> queryGroupBySeqRange(table, groupId, fromSeq, toSeq, clearedBeforeMs, safeLimit + 1),
                Comparator.comparing(item -> item.msgSeq() == null ? Long.MAX_VALUE : item.msgSeq()));
            return toPageAsc(merged, safeLimit, item -> item.msgSeq());
        }

        long cursorMs = cursor == null ? Long.MAX_VALUE : cursor;
        Instant anchor = cursor == null ? Instant.now() : Instant.ofEpochMilli(cursor);
        List<HistoryItem> merged = fetchAcrossMonths(
            anchor,
            safeLimit + 1,
            table -> queryGroup(table, groupId, cursorMs, clearedBeforeMs, safeLimit + 1));
        return toPageDesc(merged, safeLimit);
    }

    private List<HistoryItem> fetchAcrossMonths(Instant anchor, int need,
                                                  Function<String, List<HistoryItem>> queryFn) {
        List<HistoryItem> merged = new ArrayList<>();
        for (int monthOffset = 0; monthOffset < 2 && merged.size() < need; monthOffset++) {
            Instant monthInstant = anchor.atZone(java.time.ZoneOffset.UTC).minusMonths(monthOffset).toInstant();
            merged.addAll(queryFn.apply(tableRouter.physicalTable(monthInstant)));
        }
        merged.sort((a, b) -> Long.compare(b.msgTimeMs(), a.msgTimeMs()));
        if (merged.size() > need) {
            return merged.subList(0, need);
        }
        return merged;
    }

    private List<HistoryItem> fetchAcrossTables(List<String> tables, int need,
                                                  Function<String, List<HistoryItem>> queryFn,
                                                  Comparator<HistoryItem> ascending) {
        List<HistoryItem> merged = new ArrayList<>();
        for (String table : tables) {
            merged.addAll(queryFn.apply(table));
        }
        merged.sort(ascending);
        if (merged.size() > need) {
            return merged.subList(0, need);
        }
        return merged;
    }

    private HistoryPage toPageDesc(List<HistoryItem> merged, int safeLimit) {
        boolean hasMore = merged.size() > safeLimit;
        List<HistoryItem> items = hasMore ? merged.subList(0, safeLimit) : merged;
        Long nextCursor = items.isEmpty() ? null : items.get(items.size() - 1).msgTimeMs();
        return new HistoryPage(items, hasMore ? nextCursor : null, hasMore);
    }

    private HistoryPage toPageAsc(List<HistoryItem> merged, int safeLimit,
                                    Function<HistoryItem, Long> cursorFn) {
        boolean hasMore = merged.size() > safeLimit;
        List<HistoryItem> items = hasMore ? merged.subList(0, safeLimit) : merged;
        if (items.isEmpty() || !hasMore) {
            return new HistoryPage(items, null, hasMore);
        }
        Long last = cursorFn.apply(items.get(items.size() - 1));
        return new HistoryPage(items, last, true);
    }

    private List<HistoryItem> queryC2c(String table, String userId, String peerUserId,
                                       long cursorMs, long clearedBeforeMs, int limit) {
        String sql = """
            SELECT msg_key, msg_id, from_account, peer_account, group_id, msg_seq, msg_time_ms,
                   elem_type, preview_text, msg_body_json, status
            FROM %s
            WHERE chat_type = 0
              AND msg_time_ms < ?
              AND msg_time_ms > ?
              AND ((from_account = ? AND peer_account = ?) OR (from_account = ? AND peer_account = ?))
            ORDER BY msg_time_ms DESC
            LIMIT ?
            """.formatted(table);
        try {
            return jdbc.query(sql, (rs, rowNum) -> toItem(rs),
                cursorMs, clearedBeforeMs, userId, peerUserId, peerUserId, userId, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<HistoryItem> queryC2cByTimeRange(String table, String userId, String peerUserId,
                                                    long fromTimeMs, long toTimeMs,
                                                    long clearedBeforeMs, int limit) {
        String sql = """
            SELECT msg_key, msg_id, from_account, peer_account, group_id, msg_seq, msg_time_ms,
                   elem_type, preview_text, msg_body_json, status
            FROM %s
            WHERE chat_type = 0
              AND msg_time_ms >= ?
              AND msg_time_ms <= ?
              AND msg_time_ms > ?
              AND ((from_account = ? AND peer_account = ?) OR (from_account = ? AND peer_account = ?))
            ORDER BY msg_time_ms ASC
            LIMIT ?
            """.formatted(table);
        try {
            return jdbc.query(sql, (rs, rowNum) -> toItem(rs),
                fromTimeMs, toTimeMs, clearedBeforeMs, userId, peerUserId, peerUserId, userId, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<HistoryItem> queryGroup(String table, String groupId, long cursorMs,
                                         long clearedBeforeMs, int limit) {
        String sql = """
            SELECT msg_key, msg_id, from_account, peer_account, group_id, msg_seq, msg_time_ms,
                   elem_type, preview_text, msg_body_json, status
            FROM %s
            WHERE chat_type = 1
              AND group_id = ?
              AND msg_time_ms < ?
              AND msg_time_ms > ?
            ORDER BY msg_time_ms DESC
            LIMIT ?
            """.formatted(table);
        try {
            return jdbc.query(sql, (rs, rowNum) -> toItem(rs), groupId, cursorMs, clearedBeforeMs, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<HistoryItem> queryGroupBySeqRange(String table, String groupId,
                                                     long fromSeq, long toSeq,
                                                     long clearedBeforeMs, int limit) {
        String sql = """
            SELECT msg_key, msg_id, from_account, peer_account, group_id, msg_seq, msg_time_ms,
                   elem_type, preview_text, msg_body_json, status
            FROM %s
            WHERE chat_type = 1
              AND group_id = ?
              AND msg_seq >= ?
              AND msg_seq <= ?
              AND msg_time_ms > ?
            ORDER BY msg_seq ASC
            LIMIT ?
            """.formatted(table);
        try {
            return jdbc.query(sql, (rs, rowNum) -> toItem(rs),
                groupId, fromSeq, toSeq, clearedBeforeMs, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    private HistoryItem toItem(java.sql.ResultSet rs) throws java.sql.SQLException {
        String bodyJson = rs.getString("msg_body_json");
        List<Map<String, Object>> msgBody = parseBody(bodyJson);
        return new HistoryItem(
            rs.getString("msg_key"),
            rs.getString("msg_id"),
            rs.getString("from_account"),
            rs.getString("peer_account"),
            rs.getString("group_id"),
            rs.getObject("msg_seq") == null ? null : rs.getLong("msg_seq"),
            rs.getLong("msg_time_ms"),
            rs.getString("elem_type"),
            rs.getString("preview_text"),
            msgBody,
            rs.getInt("status"));
    }

    private List<Map<String, Object>> parseBody(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(bodyJson, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
