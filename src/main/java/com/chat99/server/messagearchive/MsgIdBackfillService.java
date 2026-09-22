package com.chat99.server.messagearchive;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.RoamMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 归档行回填腾讯 MsgId：C2C 漫游 + 群历史；幂等 UPDATE msg_id IS NULL。
 * 漫游窗口外拿不到的保持空。
 */
@Service
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class MsgIdBackfillService {

    private static final Logger log = LoggerFactory.getLogger(MsgIdBackfillService.class);
    private static final String SKIP_C2C_PREFIX = "archive:msgid-bf:c2c:";
    private static final String SKIP_GROUP_PREFIX = "archive:msgid-bf:group:";

    private final MessageArchiveProperties props;
    private final ChatMessageTableRouter tableRouter;
    private final ChatMessageWriteRepository writeRepository;
    private final ImAdminClient imAdmin;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public MsgIdBackfillService(MessageArchiveProperties props,
                                ChatMessageTableRouter tableRouter,
                                ChatMessageWriteRepository writeRepository,
                                ImAdminClient imAdmin,
                                @Qualifier("archiveWriteJdbc") JdbcTemplate jdbc,
                                StringRedisTemplate redis) {
        this.props = props;
        this.tableRouter = tableRouter;
        this.writeRepository = writeRepository;
        this.imAdmin = imAdmin;
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public record ResolveItem(String msgKey, String msgId) {}

    public int backfillOnce() {
        MessageArchiveProperties.MsgIdBackfill cfg = props.msgIdBackfill();
        List<String> tables = listArchiveTables();
        int tableBudget = Math.min(cfg.maxTablesPerRun(), tables.size());
        int c2cUpdated = 0;
        int groupUpdated = 0;
        int skipped = 0;
        for (int i = 0; i < tableBudget; i++) {
            String table = tables.get(i);
            writeRepository.ensureMsgIdColumn(table);
            int[] c2c = backfillC2cTable(table, cfg);
            c2cUpdated += c2c[0];
            skipped += c2c[1];
            int[] group = backfillGroupTable(table, cfg);
            groupUpdated += group[0];
            skipped += group[1];
        }
        int updated = c2cUpdated + groupUpdated;
        log.info("msgId backfill run done tables={} c2cUpdated={} groupUpdated={} skipped={}",
            tableBudget, c2cUpdated, groupUpdated, skipped);
        return updated;
    }

    /**
     * 查库 + 必要时漫游补齐并回写（仅 C2C）。
     */
    public List<ResolveItem> resolveC2c(String userId, String peerUserId, List<String> msgKeys) {
        if (userId == null || peerUserId == null || msgKeys == null || msgKeys.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String k : msgKeys) {
            if (k != null && !k.isBlank()) {
                wanted.add(k.trim());
            }
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<String, String> found = new LinkedHashMap<>();
        for (String table : listArchiveTables()) {
            writeRepository.ensureMsgIdColumn(table);
            Map<String, String> page = lookupMsgIds(table, wanted);
            found.putAll(page);
            if (found.size() >= wanted.size() && wanted.stream().allMatch(k -> {
                String v = found.get(k);
                return v != null && !v.isBlank();
            })) {
                break;
            }
        }
        List<String> missing = wanted.stream()
            .filter(k -> found.get(k) == null || found.get(k).isBlank())
            .toList();
        if (!missing.isEmpty()) {
            Map<String, String> fromRoam = roamC2cKeyToMsgId(userId, peerUserId);
            for (String key : missing) {
                String msgId = fromRoam.get(key);
                if (msgId == null || msgId.isBlank()) {
                    continue;
                }
                found.put(key, msgId);
                persistMsgId(key, msgId);
            }
        }
        List<ResolveItem> out = new ArrayList<>();
        for (String key : wanted) {
            out.add(new ResolveItem(key, found.get(key)));
        }
        return out;
    }

    /** @return [updated, skipped] */
    private int[] backfillC2cTable(String table, MessageArchiveProperties.MsgIdBackfill cfg) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                SELECT from_account, peer_account
                FROM `%s`
                WHERE chat_type = 0
                  AND (msg_id IS NULL OR msg_id = '')
                  AND peer_account IS NOT NULL
                GROUP BY from_account, peer_account
                LIMIT ?
                """.formatted(table), cfg.batchConversations());
        } catch (Exception e) {
            log.warn("msgId backfill list c2c failed table={} err={}", table, e.toString());
            return new int[] {0, 0};
        }
        int updated = 0;
        int skipped = 0;
        Set<String> seenPairs = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String a = str(row.get("from_account"));
            String b = str(row.get("peer_account"));
            if (a == null || b == null) {
                continue;
            }
            String lo = a.compareTo(b) <= 0 ? a : b;
            String hi = a.compareTo(b) <= 0 ? b : a;
            String pair = lo + "|" + hi;
            if (!seenPairs.add(pair)) {
                continue;
            }
            if (isSkipped(SKIP_C2C_PREFIX + lo + ":" + hi)) {
                skipped++;
                continue;
            }
            try {
                updated += backfillC2cConversation(a, b);
            } catch (Exception e) {
                log.warn("msgId backfill c2c failed a={} b={} err={}", a, b, e.toString());
            } finally {
                markSkipped(SKIP_C2C_PREFIX + lo + ":" + hi, cfg);
            }
            sleepQuietly(cfg.sleepMs());
        }
        return new int[] {updated, skipped};
    }

    /** @return [updated, skipped] */
    private int[] backfillGroupTable(String table, MessageArchiveProperties.MsgIdBackfill cfg) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                SELECT group_id
                FROM `%s`
                WHERE chat_type = 1
                  AND (msg_id IS NULL OR msg_id = '')
                  AND group_id IS NOT NULL
                  AND group_id <> ''
                GROUP BY group_id
                LIMIT ?
                """.formatted(table), cfg.batchGroups());
        } catch (Exception e) {
            log.warn("msgId backfill list group failed table={} err={}", table, e.toString());
            return new int[] {0, 0};
        }
        int updated = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            String groupId = str(row.get("group_id"));
            if (groupId == null) {
                continue;
            }
            if (isSkipped(SKIP_GROUP_PREFIX + groupId)) {
                skipped++;
                continue;
            }
            try {
                updated += backfillGroup(groupId, cfg);
            } catch (Exception e) {
                log.warn("msgId backfill group failed groupId={} err={}", groupId, e.toString());
            } finally {
                markSkipped(SKIP_GROUP_PREFIX + groupId, cfg);
            }
            sleepQuietly(cfg.sleepMs());
        }
        return new int[] {updated, skipped};
    }

    private int backfillC2cConversation(String userA, String userB) {
        Map<String, String> map = roamC2cKeyToMsgId(userA, userB);
        if (map.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            n += persistMsgId(e.getKey(), e.getValue());
        }
        return n;
    }

    private int backfillGroup(String groupId, MessageArchiveProperties.MsgIdBackfill cfg) {
        int pageSize = cfg.roamMaxCnt();
        int maxPages = cfg.maxPagesPerEntity();
        Long reqMsgSeq = null;
        Long lastMinSeq = null;
        int updated = 0;
        for (int page = 0; page < maxPages; page++) {
            List<RoamMessage> msgs = imAdmin.getGroupMessages(groupId, pageSize, reqMsgSeq);
            if (msgs == null || msgs.isEmpty()) {
                break;
            }
            Long pageMinSeq = null;
            int pageUpdates = 0;
            for (RoamMessage m : msgs) {
                if (m == null || m.msgSeq() == null) {
                    continue;
                }
                long seq = m.msgSeq();
                if (pageMinSeq == null || seq < pageMinSeq) {
                    pageMinSeq = seq;
                }
                String archiveKey = groupId + ":" + seq;
                String msgId = ImMessageArchiveParser.genuineMsgId(m.msgId(), m.msgKey());
                if (msgId == null) {
                    continue;
                }
                pageUpdates += persistMsgId(archiveKey, msgId);
            }
            updated += pageUpdates;
            if (pageMinSeq == null) {
                break;
            }
            if (lastMinSeq != null && pageMinSeq >= lastMinSeq) {
                break;
            }
            lastMinSeq = pageMinSeq;
            if (pageMinSeq <= 1) {
                break;
            }
            if (msgs.size() < pageSize) {
                break;
            }
            reqMsgSeq = pageMinSeq - 1;
        }
        return updated;
    }

    private Map<String, String> roamC2cKeyToMsgId(String userA, String userB) {
        Map<String, String> out = new HashMap<>();
        MessageArchiveProperties.MsgIdBackfill cfg = props.msgIdBackfill();
        int max = cfg.roamMaxCnt();
        int maxPages = cfg.maxPagesPerEntity();
        String lastMsgKey = null;
        for (int page = 0; page < maxPages; page++) {
            List<RoamMessage> merged = imAdmin.adminGetRoamMessagesMerged(userA, userB, max, lastMsgKey);
            if (merged == null || merged.isEmpty()) {
                break;
            }
            String oldestKey = null;
            long oldestTs = Long.MAX_VALUE;
            int added = 0;
            for (RoamMessage m : merged) {
                if (m == null || m.msgKey() == null || m.msgKey().isBlank()) {
                    continue;
                }
                String msgId = ImMessageArchiveParser.genuineMsgId(m.msgId(), m.msgKey());
                if (msgId != null && out.putIfAbsent(m.msgKey(), msgId) == null) {
                    added++;
                }
                if (m.msgTimeSec() < oldestTs && m.msgKey() != null && !m.msgKey().isBlank()) {
                    oldestTs = m.msgTimeSec();
                    oldestKey = m.msgKey();
                }
            }
            if (oldestKey == null || oldestKey.equals(lastMsgKey)) {
                break;
            }
            if (merged.size() < max && added == 0) {
                break;
            }
            lastMsgKey = oldestKey;
            if (merged.size() < max) {
                break;
            }
        }
        return out;
    }

    private int persistMsgId(String msgKey, String msgId) {
        int n = 0;
        for (String table : listArchiveTables()) {
            try {
                n += writeRepository.updateMsgIdIfNull(table, msgKey, msgId);
            } catch (Exception ignored) {
                // try next month table
            }
        }
        return n;
    }

    private boolean isSkipped(String key) {
        MessageArchiveProperties.MsgIdBackfill cfg = props.msgIdBackfill();
        if (!cfg.skipEnabled() || redis == null) {
            return false;
        }
        try {
            Boolean exists = redis.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("msgId backfill skip check failed key={} err={}", key, e.toString());
            return false;
        }
    }

    private void markSkipped(String key, MessageArchiveProperties.MsgIdBackfill cfg) {
        if (!cfg.skipEnabled() || redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, "1", Duration.ofDays(cfg.skipTtlDays()));
        } catch (Exception e) {
            log.warn("msgId backfill skip mark failed key={} err={}", key, e.toString());
        }
    }

    private Map<String, String> lookupMsgIds(String table, Set<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        if (keys.isEmpty()) {
            return out;
        }
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (String k : keys) {
            if (!in.isEmpty()) {
                in.append(',');
            }
            in.append('?');
            args.add(k);
        }
        try {
            jdbc.query(
                "SELECT msg_key, msg_id FROM `" + table + "` WHERE msg_key IN (" + in + ")",
                rs -> {
                    while (rs.next()) {
                        String id = rs.getString("msg_id");
                        if (id != null && !id.isBlank()) {
                            out.put(rs.getString("msg_key"), id);
                        }
                    }
                    return null;
                },
                args.toArray());
        } catch (Exception e) {
            log.debug("lookupMsgIds skip table={} err={}", table, e.toString());
        }
        return out;
    }

    private List<String> listArchiveTables() {
        try {
            List<String> fromReg = jdbc.queryForList(
                "SELECT physical_table FROM chat_message_table_registry ORDER BY table_suffix DESC",
                String.class);
            if (!fromReg.isEmpty()) {
                return fromReg;
            }
        } catch (Exception ignored) {
            // fall through
        }
        long now = System.currentTimeMillis();
        long yearAgo = now - 365L * 24 * 3600 * 1000;
        return tableRouter.physicalTablesBetween(yearAgo, now);
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
