import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentyun.TLSSigAPIv2;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * H3：从本地归档 chat_message_* 全量导入国内 IM（C2C + 群）。
 * 不改 app_setting / .env。可断点续跑。
 */
public class ImCnMigrateMessages {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Path STATE = Path.of("state");
    private static final Path CKPT_C2C = STATE.resolve("ckpt_msg_c2c.txt");
    private static final Path CKPT_GROUP = STATE.resolve("ckpt_msg_group.txt");
    private static final Path FAIL_LOG = STATE.resolve("msg_import_fail.jsonl");
    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP =
        new TypeReference<>() {};

    private final ImEndpoint dst;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPass;
    private final List<String> tables;
    private final int groupBatch;
    private final long minIntervalMs;
    private final int limitSessions;
    private final Set<String> onlyGroups;
    private final Set<String> onlyC2cPairs;
    private long lastCallAt;

    ImCnMigrateMessages(
        ImEndpoint dst,
        String jdbcUrl,
        String dbUser,
        String dbPass,
        List<String> tables,
        int groupBatch,
        double qps,
        int limitSessions,
        Set<String> onlyGroups,
        Set<String> onlyC2cPairs
    ) {
        this.dst = dst;
        this.jdbcUrl = jdbcUrl;
        this.dbUser = dbUser;
        this.dbPass = dbPass;
        this.tables = tables;
        this.groupBatch = Math.max(1, Math.min(7, groupBatch));
        this.minIntervalMs = qps <= 0 ? 200L : Math.max(50L, (long) Math.ceil(1000.0 / qps));
        this.limitSessions = Math.max(0, limitSessions);
        this.onlyGroups = onlyGroups;
        this.onlyC2cPairs = onlyC2cPairs;
    }

    public static void main(String[] args) throws Exception {
        // 后台重定向时默认全缓冲，进度/异常容易丢
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        try {
            int code = runMain(args);
            System.exit(code);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static int runMain(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 1;
        }
        String cmd = args[0].trim();
        int limit = 0;
        Set<String> onlyGroups = new LinkedHashSet<>();
        Set<String> onlyC2c = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--limit".equals(a) && i + 1 < args.length) {
                limit = Integer.parseInt(args[++i]);
            } else if ("--group".equals(a) && i + 1 < args.length) {
                onlyGroups.add(args[++i].trim());
            } else if ("--c2c".equals(a) && i + 1 < args.length) {
                onlyC2c.add(normalizePairKey(args[++i].trim()));
            } else {
                System.err.println("Unknown arg: " + a);
                usage();
                return 1;
            }
        }

        ImEndpoint dst = ImEndpoint.fromEnv("DST", 1600155864, "https://console.tim.qq.com/v4/");
        String host = env("DB_HOST", "127.0.0.1");
        String port = env("DB_PORT", "3306");
        String db = env("DB_NAME", "chat99");
        String user = env("DB_USERNAME", "chat99");
        String pass = env("DB_PASSWORD", "");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8";
        double qps = Double.parseDouble(env("MSG_IMPORT_QPS", "8"));
        int batch = Integer.parseInt(env("MSG_GROUP_BATCH", "7"));

        Files.createDirectories(STATE);
        ImCnMigrateMessages app;
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            List<String> tables = discoverTables(conn);
            app = new ImCnMigrateMessages(dst, url, user, pass, tables, batch, qps, limit, onlyGroups, onlyC2c);
            System.out.println("tables=" + tables + " qps=" + qps + " batch=" + batch
                + " limit=" + limit + " onlyGroups=" + onlyGroups.size());
        }

        return switch (cmd) {
            case "import-c2c-msgs" -> app.importC2c();
            case "import-group-msgs" -> app.importGroups();
            case "import-all-msgs" -> {
                int a = app.importC2c();
                int b = app.importGroups();
                yield (a != 0 || b != 0) ? 1 : 0;
            }
            default -> {
                usage();
                yield 1;
            }
        };
    }

    private static void usage() {
        System.err.println("Usage: ImCnMigrateMessages <import-c2c-msgs|import-group-msgs|import-all-msgs> [--limit N] [--group GID] [--c2c a:b]");
    }

    private int importC2c() throws Exception {
        Set<String> done = loadCkpt(CKPT_C2C);
        List<String> pairs = listC2cPairs();
        int ok = 0;
        int fail = 0;
        int skip = 0;
        int processed = 0;
        System.out.println("c2c pairs=" + pairs.size() + " ckpt=" + done.size());
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             BufferedWriter failOut = openFailLog()) {
            for (String pair : pairs) {
                if (done.contains(pair)) {
                    skip++;
                    continue;
                }
                if (limitSessions > 0 && processed >= limitSessions) {
                    break;
                }
                String[] ab = pair.split(":", 2);
                if (ab.length != 2) {
                    skip++;
                    continue;
                }
                List<ArchMsg> msgs = loadC2cMsgs(conn, ab[0], ab[1]);
                boolean pairOk = true;
                for (ArchMsg m : msgs) {
                    try {
                        Map<String, Object> body = buildC2cImport(m);
                        throttle();
                        Map<?, ?> resp = dst.postWithRetry("openim/importmsg", body);
                        if (!imOk(resp)) {
                            pairOk = false;
                            fail++;
                            writeFail(failOut, "c2c", pair, m.msgKey, resp);
                            // 单条失败继续同会话后续（去重后可能成功）
                        } else {
                            ok++;
                        }
                    } catch (Exception e) {
                        pairOk = false;
                        fail++;
                        writeFail(failOut, "c2c", pair, m.msgKey, Map.of("error", e.toString()));
                    }
                }
                if (pairOk || msgs.isEmpty()) {
                    appendCkpt(CKPT_C2C, pair);
                    done.add(pair);
                }
                processed++;
                if (processed % 10 == 0 || processed == 1) {
                    System.out.println("c2c progress processed=" + processed
                        + " ok=" + ok + " fail=" + fail + " skipCkpt=" + skip
                        + " ckptTotal=" + done.size());
                }
            }
        }
        System.out.println("c2c done ok=" + ok + " fail=" + fail + " skipCkpt=" + skip + " processed=" + processed);
        return fail > 0 && ok == 0 ? 1 : 0;
    }

    private int importGroups() throws Exception {
        Set<String> done = loadCkpt(CKPT_GROUP);
        List<String> groups = listGroupIds();
        int ok = 0;
        int fail = 0;
        int skip = 0;
        int skippedTooOld = 0;
        int processed = 0;
        System.out.println("group sessions=" + groups.size() + " ckpt=" + done.size());
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             BufferedWriter failOut = openFailLog()) {
            for (String gid : groups) {
                if (done.contains(gid)) {
                    skip++;
                    continue;
                }
                if (limitSessions > 0 && processed >= limitSessions) {
                    break;
                }
                long createSec = 0L;
                long latestSec = 0L;
                try {
                    GroupBound bound = fetchGroupBound(gid);
                    createSec = bound.createSec;
                    latestSec = bound.latestMsgSec;
                    if (bound.missing) {
                        writeFail(failOut, "group", gid, null, Map.of("error", "group_gone"));
                        appendCkpt(CKPT_GROUP, gid); // 不重试不存在的群
                        done.add(gid);
                        processed++;
                        continue;
                    }
                } catch (Exception e) {
                    fail++;
                    writeFail(failOut, "group", gid, null, Map.of("error", "bound:" + e));
                    processed++;
                    continue;
                }

                List<ArchMsg> msgs = loadGroupMsgs(conn, gid);
                long nowSec = System.currentTimeMillis() / 1000L;
                List<ArchMsg> eligible = new ArrayList<>();
                for (ArchMsg m : msgs) {
                    long send = m.msgTimeSec();
                    if (send <= createSec || send <= latestSec || send >= nowSec) {
                        skippedTooOld++;
                        continue;
                    }
                    eligible.add(m);
                }

                boolean groupOk = true;
                boolean permanentFail = false;
                long cursorLatest = latestSec;
                for (int i = 0; i < eligible.size(); ) {
                    List<Map<String, Object>> batch = new ArrayList<>();
                    int end = Math.min(i + groupBatch, eligible.size());
                    for (int j = i; j < end; j++) {
                        ArchMsg m = eligible.get(j);
                        long send = m.msgTimeSec();
                        if (send <= cursorLatest) {
                            continue;
                        }
                        batch.add(buildGroupMsgItem(m));
                    }
                    if (batch.isEmpty()) {
                        i = end;
                        continue;
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("GroupId", gid);
                    if (!gid.startsWith("@TGS#_")) {
                        body.put("RecentContactFlag", 1);
                    }
                    body.put("MsgList", batch);
                    try {
                        throttle();
                        Map<?, ?> resp = dst.postWithRetry("group_open_http_svc/import_group_msg", body);
                        if (!imOk(resp)) {
                            groupOk = false;
                            fail++;
                            writeFail(failOut, "group", gid, batch.size() + "msgs", resp);
                            // 时间序/非法消息等永久失败：停止该群并记断点，避免看门狗死循环
                            int code = errorCode(resp);
                            if (code == 10004 || code == 10007 || code == 10010 || code == 10015) {
                                permanentFail = true;
                                break;
                            }
                            i = end;
                            continue;
                        }
                        ok += batch.size();
                        Object last = batch.get(batch.size() - 1).get("SendTime");
                        if (last instanceof Number n) {
                            cursorLatest = Math.max(cursorLatest, n.longValue());
                        }
                    } catch (Exception e) {
                        groupOk = false;
                        fail++;
                        writeFail(failOut, "group", gid, null, Map.of("error", e.toString()));
                        break;
                    }
                    i = end;
                }

                // 永久失败也写 ckpt（已记录 fail 日志）；瞬时失败不写以便续跑
                if (groupOk || permanentFail) {
                    appendCkpt(CKPT_GROUP, gid);
                    done.add(gid);
                }
                processed++;
                if (processed % 10 == 0 || processed == 1) {
                    System.out.println("group progress processed=" + processed
                        + " okMsgs=" + ok + " fail=" + fail
                        + " skipCkpt=" + skip + " skipTime=" + skippedTooOld
                        + " ckptTotal=" + done.size());
                }
            }
        }
        System.out.println("group done okMsgs=" + ok + " fail=" + fail
            + " skipCkpt=" + skip + " skipTime=" + skippedTooOld + " processed=" + processed);
        return fail > 0 && ok == 0 ? 1 : 0;
    }

    private Map<String, Object> buildC2cImport(ArchMsg m) throws IOException {
        C2cKeyParts parts = parseC2cKey(m.msgKey, m.msgTimeSec());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("SyncFromOldSystem", 2);
        body.put("From_Account", m.fromAccount);
        body.put("To_Account", m.peerAccount);
        body.put("MsgSeq", parts.msgSeq);
        body.put("MsgRandom", parts.msgRandom);
        body.put("MsgTimeStamp", parts.msgTimeSec);
        body.put("MsgBody", parseMsgBody(m.msgBodyJson));
        return body;
    }

    private Map<String, Object> buildGroupMsgItem(ArchMsg m) throws IOException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("From_Account", m.fromAccount);
        item.put("SendTime", m.msgTimeSec());
        int random = m.msgSeq != null
            ? (int) (m.msgSeq.longValue() & 0x7fffffffL)
            : Math.floorMod(m.msgKey.hashCode(), Integer.MAX_VALUE);
        if (random == 0) {
            random = 1;
        }
        item.put("Random", random);
        item.put("MsgBody", parseMsgBody(m.msgBodyJson));
        return item;
    }

    private List<Map<String, Object>> parseMsgBody(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            return List.of(Map.of(
                "MsgType", "TIMTextElem",
                "MsgContent", Map.of("Text", "")
            ));
        }
        return OM.readValue(raw, LIST_MAP);
    }

    private GroupBound fetchGroupBound(String gid) throws IOException {
        Map<?, ?> infoResp = dst.postWithRetry("group_open_http_svc/get_group_info", Map.of(
            "GroupIdList", List.of(gid)
        ));
        if (!imOk(infoResp)) {
            int code = errorCode(infoResp);
            if (code == 10010 || code == 10015) {
                return GroupBound.missingGroup();
            }
            throw new IOException("get_group_info failed " + infoResp);
        }
        long createSec = 0L;
        Object list = infoResp.get("GroupInfo");
        if (list instanceof List<?> arr && !arr.isEmpty() && arr.get(0) instanceof Map<?, ?> g) {
            Object err = g.get("ErrorCode");
            if (err instanceof Number n && n.intValue() != 0) {
                int c = n.intValue();
                if (c == 10010 || c == 10015) {
                    return GroupBound.missingGroup();
                }
            }
            createSec = numLong(g.get("CreateTime"));
        }

        Map<?, ?> msgResp = dst.postWithRetry("group_open_http_svc/group_msg_get_simple", Map.of(
            "GroupId", gid,
            "ReqMsgNumber", 1
        ));
        long latest = 0L;
        if (imOk(msgResp)) {
            Object rsp = msgResp.get("RspMsgList");
            if (rsp instanceof List<?> msgs && !msgs.isEmpty() && msgs.get(0) instanceof Map<?, ?> m) {
                latest = Math.max(numLong(m.get("MsgTimeStamp")), numLong(m.get("MsgTime")));
            }
        }
        return new GroupBound(false, createSec, latest);
    }

    private List<String> listC2cPairs() throws Exception {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!onlyC2cPairs.isEmpty()) {
            out.addAll(onlyC2cPairs);
            return new ArrayList<>(out);
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ");
            }
            sql.append("SELECT from_account f, peer_account p FROM ").append(tables.get(i))
                .append(" WHERE chat_type=0 AND peer_account IS NOT NULL AND peer_account<>''");
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                out.add(normalizePairKey(rs.getString(1) + ":" + rs.getString(2)));
            }
        }
        return new ArrayList<>(out);
    }

    private List<String> listGroupIds() throws Exception {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!onlyGroups.isEmpty()) {
            out.addAll(onlyGroups);
            return new ArrayList<>(out);
        }
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < tables.size(); i++) {
            if (i > 0) {
                sql.append(" UNION ");
            }
            sql.append("SELECT group_id FROM ").append(tables.get(i))
                .append(" WHERE chat_type=1 AND group_id IS NOT NULL AND group_id<>''")
                .append(" AND (group_id LIKE 'm%' OR group_id REGEXP '^@TGS#_m')");
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return new ArrayList<>(out);
    }

    private List<ArchMsg> loadC2cMsgs(Connection conn, String a, String b) throws Exception {
        List<ArchMsg> out = new ArrayList<>();
        String sql = "SELECT msg_key, from_account, peer_account, group_id, msg_seq, msg_time_ms, CAST(msg_body_json AS CHAR) body "
            + "FROM %s WHERE chat_type=0 AND ("
            + "(from_account=? AND peer_account=?) OR (from_account=? AND peer_account=?)"
            + ") ORDER BY msg_time_ms ASC, id ASC";
        for (String table : tables) {
            try (PreparedStatement ps = conn.prepareStatement(sql.formatted(table))) {
                ps.setString(1, a);
                ps.setString(2, b);
                ps.setString(3, b);
                ps.setString(4, a);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(ArchMsg.fromRs(rs));
                    }
                }
            }
        }
        out.sort((x, y) -> {
            int c = Long.compare(x.msgTimeMs, y.msgTimeMs);
            return c != 0 ? c : x.msgKey.compareTo(y.msgKey);
        });
        return out;
    }

    private List<ArchMsg> loadGroupMsgs(Connection conn, String gid) throws Exception {
        List<ArchMsg> out = new ArrayList<>();
        String sql = "SELECT msg_key, from_account, peer_account, group_id, msg_seq, msg_time_ms, CAST(msg_body_json AS CHAR) body "
            + "FROM %s WHERE chat_type=1 AND group_id=? ORDER BY msg_time_ms ASC, msg_seq ASC, id ASC";
        for (String table : tables) {
            try (PreparedStatement ps = conn.prepareStatement(sql.formatted(table))) {
                ps.setString(1, gid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(ArchMsg.fromRs(rs));
                    }
                }
            }
        }
        out.sort((x, y) -> {
            int c = Long.compare(x.msgTimeMs, y.msgTimeMs);
            if (c != 0) {
                return c;
            }
            long sx = x.msgSeq == null ? 0 : x.msgSeq;
            long sy = y.msgSeq == null ? 0 : y.msgSeq;
            return Long.compare(sx, sy);
        });
        return out;
    }

    private synchronized void throttle() throws InterruptedException {
        long now = System.currentTimeMillis();
        long wait = lastCallAt + minIntervalMs - now;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastCallAt = System.currentTimeMillis();
    }

    private static List<String> discoverTables(Connection conn) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT table_name FROM information_schema.tables "
                     + "WHERE table_schema=DATABASE() AND table_name REGEXP '^chat_message_[0-9]{6}$' "
                     + "ORDER BY table_name")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("no chat_message_YYYYMM tables");
        }
        return out;
    }

    private static String normalizePairKey(String raw) {
        String s = raw.trim();
        String[] p = s.split(":", 2);
        if (p.length != 2) {
            return s;
        }
        String a = p[0].trim();
        String b = p[1].trim();
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }

    private static Set<String> loadCkpt(Path path) throws IOException {
        Set<String> out = new HashSet<>();
        if (!Files.exists(path)) {
            return out;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return out;
    }

    private static synchronized void appendCkpt(Path path, String id) throws IOException {
        Files.writeString(path, id + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static BufferedWriter openFailLog() throws IOException {
        return Files.newBufferedWriter(FAIL_LOG, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void writeFail(BufferedWriter out, String kind, String session, String msgKey, Object detail)
        throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ts", System.currentTimeMillis());
        row.put("kind", kind);
        row.put("session", session);
        row.put("msgKey", msgKey);
        row.put("detail", detail == null ? null : detail.toString());
        out.write(OM.writeValueAsString(row));
        out.newLine();
        out.flush();
    }

    private static C2cKeyParts parseC2cKey(String msgKey, long fallbackTimeSec) {
        long seq = Math.floorMod(msgKey.hashCode(), Integer.MAX_VALUE);
        int random = Math.floorMod((msgKey + "#r").hashCode(), Integer.MAX_VALUE);
        if (random == 0) {
            random = 1;
        }
        long time = fallbackTimeSec;
        if (msgKey != null) {
            String[] parts = msgKey.split("_");
            if (parts.length >= 3) {
                try {
                    seq = Long.parseUnsignedLong(parts[0]);
                    random = (int) (Long.parseUnsignedLong(parts[1]) & 0x7fffffffL);
                    if (random == 0) {
                        random = 1;
                    }
                    time = Long.parseUnsignedLong(parts[2]);
                } catch (NumberFormatException ignored) {
                    // keep hash fallback
                }
            } else if (parts.length == 2) {
                try {
                    random = (int) (Long.parseUnsignedLong(parts[0]) & 0x7fffffffL);
                    if (random == 0) {
                        random = 1;
                    }
                    time = Long.parseUnsignedLong(parts[1]);
                } catch (NumberFormatException ignored) {
                    // keep hash fallback
                }
            }
        }
        if (time <= 0) {
            time = fallbackTimeSec;
        }
        return new C2cKeyParts(seq, random, time);
    }

    private static boolean imOk(Map<?, ?> resp) {
        return resp != null && errorCode(resp) == 0;
    }

    private static int errorCode(Map<?, ?> resp) {
        if (resp == null) {
            return -1;
        }
        Object c = resp.get("ErrorCode");
        return c instanceof Number n ? n.intValue() : -1;
    }

    private static long numLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private record ArchMsg(
        String msgKey,
        String fromAccount,
        String peerAccount,
        String groupId,
        Long msgSeq,
        long msgTimeMs,
        String msgBodyJson
    ) {
        static ArchMsg fromRs(ResultSet rs) throws Exception {
            long seq = rs.getLong("msg_seq");
            Long msgSeq = rs.wasNull() ? null : seq;
            return new ArchMsg(
                rs.getString("msg_key"),
                rs.getString("from_account"),
                rs.getString("peer_account"),
                rs.getString("group_id"),
                msgSeq,
                rs.getLong("msg_time_ms"),
                rs.getString("body")
            );
        }

        long msgTimeSec() {
            return msgTimeMs / 1000L;
        }
    }

    private record C2cKeyParts(long msgSeq, int msgRandom, long msgTimeSec) {}

    private record GroupBound(boolean missing, long createSec, long latestMsgSec) {
        static GroupBound missingGroup() {
            return new GroupBound(true, 0, 0);
        }
    }

    private static final class ImEndpoint {
        private final ObjectMapper json = new ObjectMapper();
        private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        private final Random rng = new Random();
        final int sdkAppId;
        final String restBase;
        final String admin;
        final TLSSigAPIv2 sigApi;

        ImEndpoint(int sdkAppId, String restBase, String admin, String key) {
            this.sdkAppId = sdkAppId;
            this.restBase = restBase.endsWith("/") ? restBase : restBase + "/";
            this.admin = admin;
            this.sigApi = new TLSSigAPIv2(sdkAppId, key);
        }

        static ImEndpoint fromEnv(String prefix, int defaultSdk, String defaultBase) {
            int sdk = Integer.parseInt(env(prefix + "_IM_SDK_APP_ID", String.valueOf(defaultSdk)));
            String key = System.getenv(prefix + "_IM_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Missing env " + prefix + "_IM_KEY");
            }
            String base = env(prefix + "_IM_REST_BASE", defaultBase);
            String admin = env("IM_ADMIN", "administrator");
            return new ImEndpoint(sdk, base, admin, key.trim());
        }

        Map<?, ?> postWithRetry(String path, Map<String, Object> body) throws IOException {
            long sleep = 1000L;
            for (int attempt = 1; attempt <= 5; attempt++) {
                Map<?, ?> resp = post(path, body);
                if (resp != null && imOk(resp)) {
                    return resp;
                }
                int code = errorCode(resp);
                if (code == 10006 || code == 60007 || code == 70020) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    sleep = Math.min(sleep * 2, 30_000L);
                    continue;
                }
                return resp;
            }
            return post(path, body);
        }

        Map<?, ?> post(String path, Map<String, Object> body) throws IOException {
            String adminSig = sigApi.genUserSig(admin, 86400);
            long random = Integer.toUnsignedLong(rng.nextInt());
            String url = restBase + path
                + "?sdkappid=" + sdkAppId
                + "&identifier=" + admin
                + "&usersig=" + adminSig
                + "&random=" + random
                + "&contenttype=json";
            String payload = json.writeValueAsString(body);
            Request req = new Request.Builder().url(url)
                .post(RequestBody.create(payload, JSON))
                .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    System.err.println("HTTP " + resp.code() + " path=" + path);
                    return null;
                }
                @SuppressWarnings("unchecked")
                Map<?, ?> map = json.readValue(resp.body().string(), Map.class);
                return map;
            }
        }
    }
}
