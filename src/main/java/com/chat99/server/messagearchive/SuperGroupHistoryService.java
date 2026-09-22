package com.chat99.server.messagearchive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class SuperGroupHistoryService {
    private static final Logger log = LoggerFactory.getLogger(SuperGroupHistoryService.class);
    private static final int MAX_LIMIT = 200;
    private static final long CURSOR_TTL_MS = 24L * 60 * 60 * 1000;
    private final JdbcTemplate jdbc;
    private final ChatMessageTableRouter tableRouter;
    private final ObjectMapper json;
    private final byte[] signingKey;

    public SuperGroupHistoryService(@Qualifier("archiveReadJdbc") JdbcTemplate jdbc,
                                    ChatMessageTableRouter tableRouter,
                                    ObjectMapper json,
                                    @Value("${chat99.message-archive.history-cursor-secret:${JWT_SECRET_FALLBACK:change-me-history-secret}}") String secret) {
        this.jdbc = jdbc;
        this.tableRouter = tableRouter;
        this.json = json;
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record HistoryItem(String messageId, long groupSeq, String senderUserId,
                              String messageType, Object payload, long serverTimestamp,
                              int status) {}
    public record UnavailableRange(long fromSeq, long toSeq, String reason) {}
    public record HistoryResponse(String groupId, long snapshotMaxSeq, long minAvailableSeq,
                                  Long oldestSeq, Long newestSeq, int count, boolean hasMoreOlder,
                                  String olderCursor, List<UnavailableRange> unavailableRanges,
                                  String pageChecksum, List<HistoryItem> items) {}
    private record Cursor(String userId, String groupId, String direction, long snapshotMaxSeq, long anchorSeq,
                          int version, long expiresAt, long minAvailableSeq) {}

    @Transactional(readOnly = true)
    public HistoryResponse older(String userId, String groupId, int requestedLimit, String encodedCursor) {
        long started = System.currentTimeMillis();
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_GROUP");
        }
        int limit = Math.min(Math.max(requestedLimit <= 0 ? 50 : requestedLimit, 1), MAX_LIMIT);
        Cursor cursor = decodeCursor(encodedCursor);
        String cursorHash = encodedCursor == null || encodedCursor.isBlank() ? "none" : checksumText(encodedCursor);
        long snapshotMax;
        long minAvailable;
        long anchor;
        if (cursor == null) {
            snapshotMax = maxSeq(groupId);
            minAvailable = minSeq(groupId);
            anchor = snapshotMax == Long.MAX_VALUE ? Long.MAX_VALUE : snapshotMax + 1;
        } else {
            if (!userId.equals(cursor.userId())) {
                throw new ResponseStatusException(HttpStatus.GONE, "CURSOR_USER_MISMATCH");
            }
            if (!groupId.equals(cursor.groupId())) {
                throw new ResponseStatusException(HttpStatus.GONE, "CURSOR_GROUP_MISMATCH");
            }
            if (!"older".equals(cursor.direction()) || cursor.version() != 1
                || cursor.expiresAt() < System.currentTimeMillis()) {
                throw new ResponseStatusException(HttpStatus.GONE, "INVALID_CURSOR");
            }
            snapshotMax = cursor.snapshotMaxSeq();
            minAvailable = cursor.minAvailableSeq();
            anchor = cursor.anchorSeq();
            if (anchor <= 0 || (anchor < minAvailable && minAvailable > 0)) {
                throw new ResponseStatusException(HttpStatus.GONE, "HISTORY_RETENTION_EXPIRED");
            }
        }
        List<HistoryItem> queried = query(groupId, snapshotMax, anchor, limit + 1);
        boolean hasMore = queried.size() > limit;
        queried.sort(Comparator.comparingLong(HistoryItem::groupSeq));
        validate(groupId, snapshotMax, queried, hasMore);
        List<HistoryItem> rows = hasMore
            ? new ArrayList<>(queried.subList(queried.size() - limit, queried.size()))
            : queried;
        Long oldest = rows.isEmpty() ? null : rows.get(0).groupSeq();
        Long newest = rows.isEmpty() ? null : rows.get(rows.size() - 1).groupSeq();
        String next = hasMore && oldest != null
            ? encode(new Cursor(userId, groupId, "older", snapshotMax, oldest, 1,
                System.currentTimeMillis() + CURSOR_TTL_MS, minAvailable)) : "";
        String checksum = checksum(rows);
        log.info("super-group history group={} snapshotMaxSeq={} anchorSeq={} limit={} returnedCount={} oldestSeq={} newestSeq={} hasMoreOlder={} durationMs={} pageChecksum={}",
            safe(groupId), snapshotMax, anchor == Long.MAX_VALUE ? null : anchor, limit, rows.size(), oldest, newest, hasMore,
            System.currentTimeMillis() - started, checksum, 0);
        return new HistoryResponse(groupId, snapshotMax, minAvailable, oldest, newest, rows.size(), hasMore,
            next, List.of(), checksum, rows);
    }

    private List<HistoryItem> query(String groupId, long snapshotMax, long anchor, int limit) {
        List<HistoryItem> out = new ArrayList<>();
        for (String table : tables()) {
            String sql = "SELECT msg_key,msg_id,from_account,msg_seq,msg_time_ms,elem_type,msg_body_json,status "
                + "FROM " + table + " WHERE chat_type=1 AND group_id=? AND msg_seq IS NOT NULL "
                + "AND msg_seq <= ? AND msg_seq < ? ORDER BY msg_seq DESC LIMIT ?";
            try {
                out.addAll(jdbc.query(sql, (rs, n) -> new HistoryItem(
                    nonBlank(rs.getString("msg_id"), rs.getString("msg_key")),
                    rs.getLong("msg_seq"), rs.getString("from_account"), rs.getString("elem_type"),
                    parse(rs.getString("msg_body_json")), rs.getLong("msg_time_ms"), rs.getInt("status")),
                    groupId, snapshotMax, anchor, limit));
            } catch (Exception e) {
                if (!isMissingTable(e)) throw storageUnavailable(e, table);
            }
        }
        out.sort(Comparator.comparingLong(HistoryItem::groupSeq).reversed());
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    private long maxSeq(String groupId) { return aggregateSeq(groupId, "MAX"); }
    private long minSeq(String groupId) { return aggregateSeq(groupId, "MIN"); }
    private long aggregateSeq(String groupId, String fn) {
        long value = fn.equals("MAX") ? 0L : Long.MAX_VALUE;
        for (String table : tables()) {
            try {
                Long n = jdbc.queryForObject("SELECT " + fn + "(msg_seq) FROM " + table
                    + " WHERE chat_type=1 AND group_id=? AND msg_seq IS NOT NULL", Long.class, groupId);
                if (n != null) value = fn.equals("MAX") ? Math.max(value, n) : Math.min(value, n);
            } catch (Exception e) { if (!isMissingTable(e)) throw storageUnavailable(e, table); }
        }
        return value == Long.MAX_VALUE ? 0L : value;
    }
    private List<String> tables() {
        try {
            return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name REGEXP '^chat_message_[0-9]{6}$' ORDER BY table_name DESC", String.class);
        } catch (Exception e) {
            throw storageUnavailable(e, "information_schema");
        }
    }
    private void validate(String groupId, long snapshotMax, List<HistoryItem> rows, boolean hasMore) {
        long previous = Long.MIN_VALUE;
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<Long> seqs = new java.util.HashSet<>();
        for (HistoryItem item : rows) {
            if (item.groupSeq() <= previous || item.groupSeq() > snapshotMax || item.messageId() == null
                || !ids.add(item.messageId()) || !seqs.add(item.groupSeq())) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HISTORY_RANGE_INCOMPLETE");
            }
            if (previous != Long.MIN_VALUE && item.groupSeq() != previous + 1) {
                log.error("history unexplained sequence gap group={} fromSeq={} toSeq={}",
                    safe(groupId), previous + 1, item.groupSeq() - 1);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HISTORY_RANGE_INCOMPLETE");
            }
            previous = item.groupSeq();
        }
        if (hasMore && rows.isEmpty()) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HISTORY_RANGE_INCOMPLETE");
    }
    private Object parse(String body) {
        if (body == null || body.isBlank()) return Map.of();
        try { return json.readValue(body, Object.class); } catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "HISTORY_RANGE_INCOMPLETE"); }
    }
    private String checksumText(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String checksum(List<HistoryItem> rows) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (HistoryItem row : rows) md.update((row.messageId() + ":" + row.groupSeq() + ";").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private Cursor decodeCursor(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            String[] parts = value.split("\\.", 2);
            if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) badCursor();
            return json.readValue(Base64.getUrlDecoder().decode(parts[0]), Cursor.class);
        } catch (ResponseStatusException e) { throw e; } catch (Exception e) { badCursor(); return null; }
    }
    private String encode(Cursor c) {
        try { String p = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(c));
            return p + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(p));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private byte[] sign(String payload) throws Exception { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(signingKey,"HmacSHA256")); return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)); }
    private void badCursor() { throw new ResponseStatusException(HttpStatus.GONE, "INVALID_CURSOR"); }
    private ResponseStatusException storageUnavailable(Exception e, String table) { log.error("history storage unavailable table={}", table, e); return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HISTORY_STORAGE_UNAVAILABLE", e); }
    private boolean isMissingTable(Exception e) { String m=e.getMessage(); return m != null && (m.contains("doesn't exist") || m.contains("does not exist") || m.contains("not found")); }
    private static String nonBlank(String a,String b){return a!=null&&!a.isBlank()?a:b;}
    private static String safe(String s){return s.length()<=64?s.substring(0,s.length()):s.substring(0,64);}
}
