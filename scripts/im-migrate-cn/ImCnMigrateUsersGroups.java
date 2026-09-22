import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tencentyun.TLSSigAPIv2;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 第一期旁路迁移：源新加坡 IM → 目标中国 IM（用户+群组）。
 * 禁止修改生产 app_setting / rest-base-url / user-sig。
 */
public class ImCnMigrateUsersGroups {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Path STATE = Path.of("state");
    private static final Path USERS_JSONL = STATE.resolve("src_users.jsonl");
    private static final Path GROUPS_JSONL = STATE.resolve("src_groups.jsonl");
    private static final Path COMMUNITY_GROUPS_JSONL = STATE.resolve("src_community_groups.jsonl");
    private static final Path COMMUNITY_CKPT = STATE.resolve("ckpt_community.txt");
    private static final Path SKIPPED_JSON = STATE.resolve("skipped_unsupported_type.json");
    private static final Path USERS_CKPT = STATE.resolve("ckpt_users.txt");
    private static final Path GROUPS_CKPT = STATE.resolve("ckpt_groups.txt");
    private static final Path VERIFY_REPORT = STATE.resolve("verify_report.json");
    private static final Path CONFLICTS = STATE.resolve("conflict_groups.json");
    private static final Path GROUP_ID_MAP = STATE.resolve("group_id_map.jsonl");

    private static final Set<String> IMPORTABLE_TYPES = Set.of(
        "Private", "Work", "Public", "ChatRoom", "Meeting", "Community");
    private static final Set<String> SKIP_TYPES = Set.of("AVChatRoom", "BChatRoom");

    private final ObjectMapper json = new ObjectMapper();
    private final ObjectMapper jsonPretty = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build();
    private final Random rng = new Random();

    private final ImEndpoint src;
    private final ImEndpoint dst;
    private final boolean includeDbUsers;
    private final boolean strictUnknownType;

    ImCnMigrateUsersGroups(ImEndpoint src, ImEndpoint dst, boolean includeDbUsers, boolean strictUnknownType) {
        this.src = src;
        this.dst = dst;
        this.includeDbUsers = includeDbUsers;
        this.strictUnknownType = strictUnknownType;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        String cmd = args[0].trim();
        List<String> forceReimport = new ArrayList<>();
        boolean forceAll = false;
        for (int i = 1; i < args.length; i++) {
            if ("--force-reimport-group".equals(args[i]) && i + 1 < args.length) {
                forceReimport.add(args[++i].trim());
            } else if ("--force-all".equals(args[i])) {
                forceAll = true;
            } else if ("--strict".equals(args[i])) {
                System.setProperty("im.migrate.strict", "1");
            } else {
                System.err.println("Unknown arg: " + args[i]);
                usage();
                System.exit(1);
            }
        }

        ImEndpoint src = ImEndpoint.fromEnv(
            "SRC",
            20042133,
            "https://adminapisgp.im.qcloud.com/v4/");
        ImEndpoint dst = ImEndpoint.fromEnv(
            "DST",
            1600155864,
            "https://console.tim.qq.com/v4/");
        boolean includeDb = !"0".equals(env("INCLUDE_DB_USERS", "1"));
        boolean strict = "1".equals(System.getProperty("im.migrate.strict"))
            || "1".equals(env("STRICT_UNKNOWN_TYPE", "0"));

        Files.createDirectories(STATE);
        ImCnMigrateUsersGroups app = new ImCnMigrateUsersGroups(src, dst, includeDb, strict);

        int code = switch (cmd) {
            case "export-snapshot" -> app.exportSnapshot();
            case "export-community" -> app.exportCommunity();
            case "import-users" -> app.importUsers();
            case "import-groups" -> app.importGroups(forceReimport, forceAll);
            case "import-community" -> app.importCommunity(forceReimport, forceAll);
            case "verify" -> app.verify();
            case "all" -> app.runAll(forceReimport, forceAll);
            default -> {
                usage();
                yield 1;
            }
        };
        System.exit(code);
    }

    private static void usage() {
        System.err.println("Usage: ImCnMigrateUsersGroups "
            + "<export-snapshot|export-community|import-users|import-groups|import-community|verify|all> "
            + "[--force-reimport-group <gid>] [--force-all] [--strict]");
    }

    private int runAll(List<String> forceReimport, boolean forceAll) throws Exception {
        int c;
        c = exportSnapshot();
        if (c != 0) {
            return c;
        }
        c = importUsers();
        if (c != 0) {
            return c;
        }
        c = importGroups(forceReimport, forceAll);
        if (c != 0) {
            return c;
        }
        return verify();
    }

    // -------------------- export --------------------

    private int exportSnapshot() throws Exception {
        System.out.println("export-snapshot: scanning source groups sdkAppId=" + src.sdkAppId);
        GroupScan scan = src.scanAllGroupIds(200_000);
        if (scan.truncated) {
            System.err.println("FATAL: source group list truncated imTotal=" + scan.imTotal
                + " got=" + scan.ids.size());
            return 2;
        }
        System.out.println("source groups: " + scan.ids.size() + " (imTotal=" + scan.imTotal + ")");

        LinkedHashSet<String> userIds = new LinkedHashSet<>();
        userIds.add(src.admin);
        List<Map<String, Object>> skipped = new ArrayList<>();

        try (BufferedWriter gw = Files.newBufferedWriter(GROUPS_JSONL, StandardCharsets.UTF_8)) {
            int n = 0;
            for (String gid : scan.ids) {
                Map<String, Object> info = src.getGroupInfo(gid);
                if (info == null) {
                    System.err.println("WARN: get_group_info failed groupId=" + gid);
                    continue;
                }
                String type = str(info.get("Type"));
                if (type != null && SKIP_TYPES.contains(type)) {
                    skipped.add(Map.of("groupId", gid, "type", type, "reason", "unsupported_type"));
                    continue;
                }
                if (type != null && !IMPORTABLE_TYPES.contains(type)) {
                    if (strictUnknownType) {
                        System.err.println("FATAL: unknown group type=" + type + " groupId=" + gid);
                        return 2;
                    }
                    skipped.add(Map.of("groupId", gid, "type", type, "reason", "unknown_type"));
                    continue;
                }

                List<Map<String, Object>> members = src.listAllMembers(gid);
                String owner = str(info.get("Owner_Account"));
                if (owner != null) {
                    userIds.add(owner);
                }
                for (Map<String, Object> m : members) {
                    String uid = str(m.get("Member_Account"));
                    if (uid != null) {
                        userIds.add(uid);
                    }
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("groupId", gid);
                row.put("type", type);
                row.put("name", str(info.get("Name")));
                row.put("ownerAccount", owner);
                row.put("faceUrl", str(info.get("FaceUrl")));
                row.put("notification", str(info.get("Notification")));
                row.put("maxMemberNum", info.get("MaxMemberNum"));
                row.put("applyJoinOption", str(info.get("ApplyJoinOption")));
                row.put("inviteJoinOption", str(info.get("InviteJoinOption")));
                row.put("createTime", info.get("CreateTime"));
                row.put("memberNum", info.get("MemberNum"));
                row.put("members", members);
                gw.write(json.writeValueAsString(row));
                gw.newLine();
                n++;
                if (n % 50 == 0) {
                    System.out.println("  exported groups " + n + "/" + scan.ids.size());
                }
                throttle();
            }
            System.out.println("exported groups rows=" + n);
        }

        jsonPretty.writeValue(SKIPPED_JSON.toFile(), skipped);
        System.out.println("skipped groups=" + skipped.size() + " -> " + SKIPPED_JSON);

        if (includeDbUsers) {
            for (String uid : loadDbUserIds()) {
                if (uid != null && !uid.isBlank()) {
                    userIds.add(uid.trim());
                }
            }
            System.out.println("after DB users merge candidates=" + userIds.size());
        }

        LinkedHashSet<String> imported = filterImportedOnSource(userIds);
        System.out.println("source Imported accounts=" + imported.size() + " (from " + userIds.size() + ")");

        try (BufferedWriter uw = Files.newBufferedWriter(USERS_JSONL, StandardCharsets.UTF_8)) {
            List<String> batch = new ArrayList<>();
            for (String uid : imported) {
                batch.add(uid);
                if (batch.size() >= 50) {
                    writeUserPortraits(uw, batch);
                    batch.clear();
                    throttle();
                }
            }
            if (!batch.isEmpty()) {
                writeUserPortraits(uw, batch);
            }
        }
        System.out.println("wrote " + USERS_JSONL + " and " + GROUPS_JSONL);
        return 0;
    }

    /** 仅社群：源 IM GroupType=Community + 业务库残留 @TGS#_ ID。 */
    private int exportCommunity() throws Exception {
        System.out.println("export-community: scanning Community sdkAppId=" + src.sdkAppId);
        GroupScan scan = src.scanAllGroupIds(200_000, "Community");
        if (scan.truncated) {
            System.err.println("FATAL: community list truncated imTotal=" + scan.imTotal
                + " got=" + scan.ids.size());
            return 2;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(scan.ids);
        System.out.println("source Community from IM: " + ids.size() + " (imTotal=" + scan.imTotal + ")");

        for (String dbId : loadDbCommunityGroupIds()) {
            ids.add(dbId);
        }
        System.out.println("after DB leftover merge candidates=" + ids.size());

        LinkedHashSet<String> userIds = new LinkedHashSet<>();
        userIds.add(src.admin);
        int n = 0;
        int failInfo = 0;
        try (BufferedWriter gw = Files.newBufferedWriter(COMMUNITY_GROUPS_JSONL, StandardCharsets.UTF_8)) {
            for (String gid : ids) {
                Map<String, Object> info = src.getGroupInfo(gid);
                if (info == null) {
                    System.err.println("WARN: get_group_info failed community=" + gid);
                    failInfo++;
                    continue;
                }
                String type = str(info.get("Type"));
                if (type == null || !"Community".equalsIgnoreCase(type)) {
                    type = "Community";
                }
                List<Map<String, Object>> members = src.listAllMembers(gid, true);
                String owner = str(info.get("Owner_Account"));
                if (owner != null) {
                    userIds.add(owner);
                }
                for (Map<String, Object> m : members) {
                    String uid = str(m.get("Member_Account"));
                    if (uid != null) {
                        userIds.add(uid);
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("groupId", gid);
                row.put("type", type);
                row.put("name", str(info.get("Name")));
                row.put("ownerAccount", owner);
                row.put("faceUrl", str(info.get("FaceUrl")));
                row.put("notification", str(info.get("Notification")));
                row.put("maxMemberNum", info.get("MaxMemberNum"));
                row.put("applyJoinOption", str(info.get("ApplyJoinOption")));
                row.put("inviteJoinOption", str(info.get("InviteJoinOption")));
                row.put("createTime", info.get("CreateTime"));
                row.put("memberNum", info.get("MemberNum"));
                row.put("supportTopic", info.get("SupportTopic"));
                row.put("members", members);
                gw.write(json.writeValueAsString(row));
                gw.newLine();
                n++;
                if (n % 20 == 0) {
                    System.out.println("  exported community " + n + "/" + ids.size());
                }
                throttle();
            }
        }
        System.out.println("exported community rows=" + n + " get_group_info_fail=" + failInfo);

        LinkedHashSet<String> imported = filterImportedOnSource(userIds);
        Path cu = STATE.resolve("src_community_users.jsonl");
        try (BufferedWriter uw = Files.newBufferedWriter(cu, StandardCharsets.UTF_8)) {
            List<String> batch = new ArrayList<>();
            for (String uid : imported) {
                batch.add(uid);
                if (batch.size() >= 50) {
                    writeUserPortraits(uw, batch);
                    batch.clear();
                    throttle();
                }
            }
            if (!batch.isEmpty()) {
                writeUserPortraits(uw, batch);
            }
        }
        System.out.println("wrote " + COMMUNITY_GROUPS_JSONL + " and " + cu);
        return n > 0 || failInfo == 0 ? 0 : 2;
    }

    private List<String> loadDbCommunityGroupIds() {
        List<String> out = new ArrayList<>();
        String host = env("DB_HOST", "127.0.0.1");
        String port = env("DB_PORT", "3306");
        String db = env("DB_NAME", "chat99");
        String user = env("DB_USERNAME", "chat99");
        String pass = env("DB_PASSWORD", "chat99");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT group_id FROM group_profile WHERE group_id REGEXP '^@TGS#_'")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            System.out.println("DB community leftover ids=" + out.size());
        } catch (Exception e) {
            System.err.println("WARN: DB community ids load skipped: " + e.getMessage());
        }
        return out;
    }

    private int importCommunity(List<String> forceReimport, boolean forceAll) throws Exception {
        if (!Files.exists(COMMUNITY_GROUPS_JSONL)) {
            System.err.println("missing " + COMMUNITY_GROUPS_JSONL + " ; run export-community first");
            return 2;
        }
        Path cu = STATE.resolve("src_community_users.jsonl");
        if (Files.exists(cu)) {
            Set<String> doneUsers = loadLines(USERS_CKPT);
            List<Map<String, Object>> pending = new ArrayList<>();
            try (BufferedReader br = Files.newBufferedReader(cu, StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = json.readValue(line, Map.class);
                    String uid = str(row.get("userId"));
                    if (uid == null || doneUsers.contains(uid)) {
                        continue;
                    }
                    pending.add(row);
                }
            }
            if (!pending.isEmpty()) {
                System.out.println("import-community: importing extra users " + pending.size());
                for (int i = 0; i < pending.size(); i += 100) {
                    List<Map<String, Object>> batch = pending.subList(i, Math.min(i + 100, pending.size()));
                    List<Map<String, Object>> accountList = new ArrayList<>();
                    for (Map<String, Object> row : batch) {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("UserID", str(row.get("userId")));
                        a.put("Nick", row.get("nick") == null ? "" : String.valueOf(row.get("nick")));
                        a.put("FaceUrl", row.get("faceUrl") == null ? "" : String.valueOf(row.get("faceUrl")));
                        accountList.add(a);
                    }
                    Map<?, ?> resp = dst.postWithRetry("im_open_login_svc/multiaccount_import",
                        Map.of("AccountList", accountList));
                    if (resp != null && imOk(resp)) {
                        for (Map<String, Object> row : batch) {
                            appendCkpt(USERS_CKPT, str(row.get("userId")));
                        }
                    } else {
                        for (Map<String, Object> row : batch) {
                            String uid = str(row.get("userId"));
                            Map<?, ?> one = dst.postWithRetry("im_open_login_svc/account_import", Map.of(
                                "Identifier", uid,
                                "Nick", row.get("nick") == null ? "" : String.valueOf(row.get("nick")),
                                "FaceUrl", row.get("faceUrl") == null ? "" : String.valueOf(row.get("faceUrl"))));
                            if (one != null && imOk(one)) {
                                appendCkpt(USERS_CKPT, uid);
                            } else {
                                System.err.println("WARN: community user import fail " + uid + " resp=" + one);
                            }
                        }
                    }
                    throttle();
                }
            }
        }

        Set<String> force = new HashSet<>(forceReimport);
        Set<String> done = loadLines(COMMUNITY_CKPT);
        done.addAll(loadLines(GROUPS_CKPT));
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (forceAll) {
            System.out.println("import-community: FORCE-ALL destroy+reimport enabled");
        }
        int ok = 0;
        int fail = 0;
        try (BufferedReader br = Files.newBufferedReader(COMMUNITY_GROUPS_JSONL, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = json.readValue(line, Map.class);
                String gid = str(row.get("groupId"));
                if (gid == null) {
                    continue;
                }
                if (done.contains(gid) && !forceAll && !force.contains(gid)) {
                    continue;
                }
                try {
                    importOneGroup(row, forceAll || force.contains(gid), conflicts);
                    appendCkpt(COMMUNITY_CKPT, gid);
                    ok++;
                    if (ok % 10 == 0) {
                        System.out.println("  community imported " + ok);
                    }
                } catch (Exception e) {
                    fail++;
                    System.err.println("FAIL community=" + gid + " : " + e.getMessage());
                }
                throttle();
            }
        }
        if (!conflicts.isEmpty()) {
            jsonPretty.writeValue(STATE.resolve("conflict_community.json").toFile(), conflicts);
        }
        System.out.println("import-community done ok=" + ok + " fail=" + fail + " forceAll=" + forceAll);
        return fail == 0 ? 0 : 2;
    }

    private void writeUserPortraits(BufferedWriter uw, List<String> uids) throws IOException {
        Map<String, Profile> profiles = src.getPortraits(uids);
        for (String uid : uids) {
            Profile p = profiles.getOrDefault(uid, new Profile("", ""));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", uid);
            row.put("nick", p.nick == null ? "" : p.nick);
            row.put("faceUrl", p.faceUrl == null ? "" : p.faceUrl);
            uw.write(json.writeValueAsString(row));
            uw.newLine();
        }
    }

    private LinkedHashSet<String> filterImportedOnSource(LinkedHashSet<String> candidates) throws IOException {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> list = new ArrayList<>(candidates);
        for (int i = 0; i < list.size(); i += 100) {
            List<String> batch = list.subList(i, Math.min(i + 100, list.size()));
            Map<String, Boolean> status = src.accountCheck(batch);
            for (String uid : batch) {
                if (Boolean.TRUE.equals(status.get(uid))) {
                    out.add(uid);
                }
            }
            throttle();
        }
        if (!out.contains(src.admin)) {
            // administrator may always exist; keep attempting import later
            out.add(src.admin);
        }
        return out;
    }

    private List<String> loadDbUserIds() {
        List<String> out = new ArrayList<>();
        String host = env("DB_HOST", "127.0.0.1");
        String port = env("DB_PORT", "3306");
        String db = env("DB_NAME", "chat99");
        String user = env("DB_USERNAME", "chat99");
        String pass = env("DB_PASSWORD", "chat99");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT user_id FROM users")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            System.out.println("DB users loaded=" + out.size());
        } catch (Exception e) {
            System.err.println("WARN: DB users load skipped: " + e.getMessage());
        }
        return out;
    }

    // -------------------- import users --------------------

    private int importUsers() throws Exception {
        if (!Files.exists(USERS_JSONL)) {
            System.err.println("missing " + USERS_JSONL + " ; run export-snapshot first");
            return 2;
        }
        Set<String> done = loadLines(USERS_CKPT);
        System.out.println("import-users: checkpoint done=" + done.size());

        // ensure admin on target
        Map<?, ?> adminResp = dst.postWithRetry("im_open_login_svc/account_import", Map.of(
            "Identifier", dst.admin,
            "Nick", "administrator",
            "FaceUrl", ""));
        if (adminResp == null || !imOk(adminResp)) {
            System.err.println("WARN: target admin import resp=" + adminResp);
        }

        List<Map<String, Object>> pending = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(USERS_JSONL, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = json.readValue(line, Map.class);
                String uid = str(row.get("userId"));
                if (uid == null || done.contains(uid)) {
                    continue;
                }
                pending.add(row);
            }
        }
        System.out.println("import-users pending=" + pending.size());

        List<String> fail = new ArrayList<>();
        for (int i = 0; i < pending.size(); i += 100) {
            List<Map<String, Object>> batch = pending.subList(i, Math.min(i + 100, pending.size()));
            List<Map<String, Object>> accountList = new ArrayList<>();
            for (Map<String, Object> row : batch) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("UserID", str(row.get("userId")));
                a.put("Nick", row.get("nick") == null ? "" : String.valueOf(row.get("nick")));
                a.put("FaceUrl", row.get("faceUrl") == null ? "" : String.valueOf(row.get("faceUrl")));
                accountList.add(a);
            }
            Map<?, ?> resp = dst.postWithRetry("im_open_login_svc/multiaccount_import",
                Map.of("AccountList", accountList));
            if (resp != null && imOk(resp)) {
                for (Map<String, Object> row : batch) {
                    appendCkpt(USERS_CKPT, str(row.get("userId")));
                }
            } else {
                System.err.println("batch multiaccount_import failed, fallback single; resp=" + resp);
                for (Map<String, Object> row : batch) {
                    String uid = str(row.get("userId"));
                    Map<?, ?> one = dst.postWithRetry("im_open_login_svc/account_import", Map.of(
                        "Identifier", uid,
                        "Nick", row.get("nick") == null ? "" : String.valueOf(row.get("nick")),
                        "FaceUrl", row.get("faceUrl") == null ? "" : String.valueOf(row.get("faceUrl"))));
                    if (one != null && imOk(one)) {
                        appendCkpt(USERS_CKPT, uid);
                    } else {
                        fail.add(uid);
                        System.err.println("  account_import fail userId=" + uid + " resp=" + one);
                    }
                }
            }
            System.out.println("  users progress " + Math.min(i + 100, pending.size()) + "/" + pending.size());
            throttle();
        }

        if (!fail.isEmpty()) {
            Files.writeString(STATE.resolve("failed_users.txt"),
                String.join("\n", fail) + "\n", StandardCharsets.UTF_8);
            System.err.println("import-users failures=" + fail.size());
            return 2;
        }
        System.out.println("import-users OK");
        return 0;
    }

    // -------------------- import groups --------------------

    private int importGroups(List<String> forceReimport, boolean forceAll) throws Exception {
        if (!Files.exists(GROUPS_JSONL)) {
            System.err.println("missing " + GROUPS_JSONL);
            return 2;
        }
        Set<String> force = new HashSet<>(forceReimport);
        Set<String> done = loadLines(GROUPS_CKPT);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (Files.exists(CONFLICTS)) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> prev = json.readValue(CONFLICTS.toFile(), List.class);
            if (prev != null) {
                conflicts.addAll(prev);
            }
        }

        if (forceAll) {
            System.out.println("import-groups: FORCE-ALL destroy+reimport enabled");
        }

        int ok = 0;
        int fail = 0;
        try (BufferedReader br = Files.newBufferedReader(GROUPS_JSONL, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = json.readValue(line, Map.class);
                String gid = str(row.get("groupId"));
                if (gid == null) {
                    continue;
                }
                if (done.contains(gid) && !forceAll && !force.contains(gid)) {
                    continue;
                }
                try {
                    importOneGroup(row, forceAll || force.contains(gid), conflicts);
                    appendCkpt(GROUPS_CKPT, gid);
                    ok++;
                    if (ok % 20 == 0) {
                        System.out.println("  groups imported " + ok);
                    }
                } catch (Exception e) {
                    fail++;
                    System.err.println("FAIL groupId=" + gid + " : " + e.getMessage());
                }
                throttle();
            }
        }
        jsonPretty.writeValue(CONFLICTS.toFile(), conflicts);
        System.out.println("import-groups done ok=" + ok + " fail=" + fail + " conflicts=" + conflicts.size()
            + " forceAll=" + forceAll);
        return fail == 0 ? 0 : 2;
    }

    @SuppressWarnings("unchecked")
    private void importOneGroup(Map<String, Object> row, boolean force, List<Map<String, Object>> conflicts)
        throws IOException {
        String srcGid = str(row.get("groupId"));
        String type = str(row.get("type"));
        String owner = str(row.get("ownerAccount"));
        String name = str(row.get("name"));
        if (srcGid == null || type == null || owner == null || name == null || name.isBlank()) {
            throw new IOException("incomplete group row");
        }
        String dstGid = toDstGroupId(srcGid, type);

        ensureUsersOnTarget(collectMemberIds(row));

        Map<String, Object> existing = dst.getGroupInfo(dstGid);
        if (existing != null) {
            String existOwner = str(existing.get("Owner_Account"));
            if (existOwner != null && !existOwner.equals(owner) && !force) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("srcGroupId", srcGid);
                c.put("dstGroupId", dstGid);
                c.put("dstOwner", existOwner);
                c.put("srcOwner", owner);
                c.put("reason", "owner_mismatch");
                conflicts.add(c);
                throw new IOException("conflict owner dst=" + existOwner + " src=" + owner
                    + " ; use --force-reimport-group " + srcGid);
            }
            if (force) {
                Map<?, ?> dest = dst.postWithRetry("group_open_http_svc/destroy_group", Map.of("GroupId", dstGid));
                if (dest == null || !imOk(dest)) {
                    throw new IOException("destroy_group failed resp=" + dest);
                }
                existing = null;
            }
        }

        long createTime = numLong(row.get("createTime"));
        if (existing == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Type", type);
            body.put("Name", name);
            body.put("GroupId", dstGid);
            body.put("Owner_Account", owner);
            String face = str(row.get("faceUrl"));
            if (face != null && !face.isBlank()) {
                body.put("FaceUrl", face);
            }
            String notice = str(row.get("notification"));
            if (notice != null && !notice.isBlank()) {
                body.put("Notification", notice);
            }
            Object max = row.get("maxMemberNum");
            if (max instanceof Number) {
                body.put("MaxMemberNum", ((Number) max).intValue());
            }
            boolean community = "Community".equalsIgnoreCase(type) || srcGid.startsWith("@TGS#_");
            if (!community) {
                String apply = str(row.get("applyJoinOption"));
                if (apply != null && !apply.isBlank()) {
                    body.put("ApplyJoinOption", apply);
                }
            } else {
                Object st = row.get("supportTopic");
                if (st instanceof Number) {
                    body.put("SupportTopic", ((Number) st).intValue());
                } else {
                    body.put("SupportTopic", 0);
                }
            }
            if (createTime > 0) {
                body.put("CreateTime", createTime);
            }
            Map<?, ?> resp = dst.postWithRetry("group_open_http_svc/import_group", body);
            if (resp == null || !imOk(resp)) {
                if (resp != null && errorCode(resp) == 10021) {
                    System.err.println("WARN: group already exists " + dstGid + ", sync members only");
                } else {
                    throw new IOException("import_group failed src=" + srcGid + " dst=" + dstGid + " resp=" + resp);
                }
            } else {
                String returned = str(resp.get("GroupId"));
                if (returned != null && !returned.isBlank()) {
                    dstGid = returned;
                }
            }
        } else if (!owner.equals(str(existing.get("Owner_Account")))) {
            Map<?, ?> ch = dst.postWithRetry("group_open_http_svc/change_group_owner", Map.of(
                "GroupId", dstGid,
                "NewOwner_Account", owner));
            if (ch == null || !imOk(ch)) {
                throw new IOException("change_group_owner failed resp=" + ch);
            }
        }

        appendGroupIdMap(srcGid, dstGid);

        List<Map<String, Object>> members = (List<Map<String, Object>>) row.get("members");
        if (members == null) {
            members = List.of();
        }

        Set<String> dstMembers = new HashSet<>(dst.listAllMemberIds(dstGid));
        List<Map<String, Object>> toImport = new ArrayList<>();
        List<Map<String, Object>> nameCards = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        for (Map<String, Object> m : members) {
            String uid = str(m.get("Member_Account"));
            if (uid == null || uid.equals(owner)) {
                continue;
            }
            String role = str(m.get("Role"));
            String nameCard = str(m.get("NameCard"));
            if (nameCard != null && !nameCard.isBlank()) {
                nameCards.add(Map.of("userId", uid, "nameCard", nameCard));
            }
            if (dstMembers.contains(uid)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("Member_Account", uid);
            if ("Admin".equalsIgnoreCase(role)) {
                item.put("Role", "Admin");
            }
            long join = numLong(m.get("JoinTime"));
            long safeJoin = clampJoinTime(createTime, join, now);
            if (safeJoin > 0) {
                item.put("JoinTime", safeJoin);
            }
            toImport.add(item);
        }

        for (int i = 0; i < toImport.size(); i += 20) {
            List<Map<String, Object>> batch = toImport.subList(i, Math.min(i + 20, toImport.size()));
            Map<?, ?> resp = dst.postWithRetry("group_open_http_svc/import_group_member", Map.of(
                "GroupId", dstGid,
                "MemberList", batch));
            if (resp == null || !imOk(resp)) {
                throw new IOException("import_group_member failed dst=" + dstGid + " resp=" + resp);
            }
            throttle();
        }

        for (Map<String, Object> nc : nameCards) {
            String uid = str(nc.get("userId"));
            String card = str(nc.get("nameCard"));
            Map<?, ?> resp = dst.postWithRetry("group_open_http_svc/modify_group_member_info", Map.of(
                "GroupId", dstGid,
                "Member_Account", uid,
                "NameCard", card));
            if (resp == null || !imOk(resp)) {
                System.err.println("WARN: NameCard fail group=" + dstGid + " user=" + uid);
            }
        }
    }

    /**
     * 国内 import_group 禁止自定义 ID 带 @TGS# 前缀。
     * 源系统自动 ID（@TGS#xxx）映射为稳定自定义 ID：m + 去掉前缀后的后缀。
     * Community 官方要求前缀 @TGS#_ 。
     */
    static String toDstGroupId(String srcGroupId, String type) {
        String src = srcGroupId.trim();
        boolean community = (type != null && "Community".equalsIgnoreCase(type.trim()))
            || src.startsWith("@TGS#_");
        String core;
        if (src.startsWith("@TGS#_")) {
            core = src.substring("@TGS#_".length());
            if (core.startsWith("@TGS#")) {
                core = core.substring("@TGS#".length());
            }
        } else if (src.startsWith("@TGS#")) {
            core = src.substring("@TGS#".length());
        } else {
            core = src;
            if (community && !src.startsWith("@TGS#_")) {
                String id = "@TGS#_m" + core;
                return truncateGroupId(id);
            }
            return truncateGroupId(src.startsWith("m") || src.startsWith("@") ? src : "m" + src);
        }
        if (core.isEmpty()) {
            core = "x";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (c >= 0x20 && c <= 0x7e && c != '@' && c != '#') {
                sb.append(c);
            }
        }
        if (sb.length() == 0) {
            sb.append("x");
        }
        String id = community ? ("@TGS#_m" + sb) : ("m" + sb);
        return truncateGroupId(id);
    }

    private static String truncateGroupId(String id) {
        if (id.length() <= 48) {
            return id;
        }
        return id.substring(0, 48);
    }

    private synchronized void appendGroupIdMap(String srcGroupId, String dstGroupId) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("srcGroupId", srcGroupId);
        row.put("dstGroupId", dstGroupId);
        Files.writeString(GROUP_ID_MAP, json.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
            Files.exists(GROUP_ID_MAP)
                ? java.nio.file.StandardOpenOption.APPEND
                : java.nio.file.StandardOpenOption.CREATE);
    }

    private Map<String, String> loadGroupIdMap() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.exists(GROUP_ID_MAP)) {
            return map;
        }
        try (BufferedReader br = Files.newBufferedReader(GROUP_ID_MAP, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = json.readValue(line, Map.class);
                String s = str(row.get("srcGroupId"));
                String d = str(row.get("dstGroupId"));
                if (s != null && d != null) {
                    map.put(s, d);
                }
            }
        }
        return map;
    }

    private void ensureUsersOnTarget(Set<String> userIds) throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> list = new ArrayList<>(userIds);
        for (int i = 0; i < list.size(); i += 100) {
            List<String> batch = list.subList(i, Math.min(i + 100, list.size()));
            Map<String, Boolean> st = dst.accountCheck(batch);
            for (String uid : batch) {
                if (!Boolean.TRUE.equals(st.get(uid))) {
                    missing.add(uid);
                }
            }
        }
        for (String uid : missing) {
            Map<?, ?> resp = dst.postWithRetry("im_open_login_svc/account_import", Map.of(
                "Identifier", uid,
                "Nick", "",
                "FaceUrl", ""));
            if (resp == null || !imOk(resp)) {
                throw new IOException("ensure account failed userId=" + uid + " resp=" + resp);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> collectMemberIds(Map<String, Object> row) {
        Set<String> ids = new LinkedHashSet<>();
        String owner = str(row.get("ownerAccount"));
        if (owner != null) {
            ids.add(owner);
        }
        Object members = row.get("members");
        if (members instanceof List<?> arr) {
            for (Object o : arr) {
                if (o instanceof Map<?, ?> m) {
                    String uid = str(m.get("Member_Account"));
                    if (uid != null) {
                        ids.add(uid);
                    }
                }
            }
        }
        return ids;
    }

    private static long clampJoinTime(long createTime, long join, long now) {
        long jt = join > 0 ? join : (createTime > 0 ? createTime + 1 : now - 1);
        if (createTime > 0 && jt <= createTime) {
            jt = createTime + 1;
        }
        if (jt >= now) {
            jt = now - 1;
        }
        if (createTime > 0 && jt <= createTime) {
            return 0; // skip invalid
        }
        return jt;
    }

    // -------------------- verify --------------------

    private int verify() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("cutover", false);
        report.put("manualChecks", List.of(
            "Confirm production log still sdkAppId=20042133 restBase=adminapisgp",
            "Confirm live traffic callbacks still use source sdkappid"));

        List<Map<String, Object>> users = readJsonl(USERS_JSONL);
        List<Map<String, Object>> groups = readJsonl(GROUPS_JSONL);

        List<String> userFail = new ArrayList<>();
        List<String> allUids = new ArrayList<>();
        for (Map<String, Object> u : users) {
            String uid = str(u.get("userId"));
            if (uid != null) {
                allUids.add(uid);
            }
        }
        for (int i = 0; i < allUids.size(); i += 100) {
            List<String> batch = allUids.subList(i, Math.min(i + 100, allUids.size()));
            Map<String, Boolean> st = dst.accountCheck(batch);
            for (String uid : batch) {
                if (!Boolean.TRUE.equals(st.get(uid))) {
                    userFail.add(uid);
                }
            }
            throttle();
        }
        report.put("accountCheckFailCount", userFail.size());
        report.put("accountCheckFailSample", userFail.stream().limit(20).toList());

        // profile sample（跳过管理员：目标侧 bootstrap 昵称可能与源不一致）
        String adminId = dst.admin;
        List<String> samplePool = new ArrayList<>();
        for (Map<String, Object> u : users) {
            String uid = str(u.get("userId"));
            if (uid == null || uid.equals(adminId)) {
                continue;
            }
            samplePool.add(uid);
        }
        int sampleN = Math.min(50, samplePool.size());
        List<String> sampleIds = new ArrayList<>();
        for (int i = 0; i < sampleN; i++) {
            sampleIds.add(samplePool.get(i));
        }
        Map<String, Profile> srcP = src.getPortraits(sampleIds);
        Map<String, Profile> dstP = dst.getPortraits(sampleIds);
        int profileMismatch = 0;
        List<String> profileSamples = new ArrayList<>();
        for (String uid : sampleIds) {
            Profile a = srcP.getOrDefault(uid, new Profile("", ""));
            Profile b = dstP.getOrDefault(uid, new Profile("", ""));
            String nickA = a.nick == null ? "" : a.nick;
            String nickB = b.nick == null ? "" : b.nick;
            String faceA = a.faceUrl == null ? "" : a.faceUrl;
            String faceB = b.faceUrl == null ? "" : b.faceUrl;
            if (!nickA.equals(nickB) || !faceA.equals(faceB)) {
                profileMismatch++;
                if (profileSamples.size() < 10) {
                    profileSamples.add(uid);
                }
            }
        }
        report.put("profileSampleSize", sampleN);
        report.put("profileMismatch", profileMismatch);
        report.put("profileMismatchSample", profileSamples);

        GroupScan dstScan = dst.scanAllGroupIds(200_000);
        Map<String, String> idMap = loadGroupIdMap();
        // 映射表缺失时按规则推导（幂等）
        for (Map<String, Object> g : groups) {
            String srcGid = str(g.get("groupId"));
            String type = str(g.get("type"));
            if (srcGid != null && !idMap.containsKey(srcGid)) {
                idMap.put(srcGid, toDstGroupId(srcGid, type));
            }
        }
        Set<String> dstGids = new LinkedHashSet<>(dstScan.ids);
        List<String> missingGroups = new ArrayList<>();
        for (Map.Entry<String, String> e : idMap.entrySet()) {
            if (!dstGids.contains(e.getValue())) {
                // 仅统计 snapshot 内的群
                boolean inSnapshot = false;
                for (Map<String, Object> g : groups) {
                    if (e.getKey().equals(str(g.get("groupId")))) {
                        inSnapshot = true;
                        break;
                    }
                }
                if (inSnapshot) {
                    missingGroups.add(e.getKey() + "->" + e.getValue());
                }
            }
        }
        report.put("expectedImportableGroups", groups.size());
        report.put("groupIdMapSize", idMap.size());
        report.put("dstGroupScanCount", dstScan.ids.size());
        report.put("dstGroupScanTruncated", dstScan.truncated);
        report.put("missingGroupsCount", missingGroups.size());
        report.put("missingGroupsSample", missingGroups.stream().limit(20).toList());
        report.put("groupIdStrategy", "custom_map_strip_TGS_prefix");

        int ownerMismatch = 0;
        int memberMismatch = 0;
        int adminMismatch = 0;
        List<String> ownerFail = new ArrayList<>();
        List<String> memberFail = new ArrayList<>();
        for (Map<String, Object> g : groups) {
            String srcGid = str(g.get("groupId"));
            if (srcGid == null) {
                continue;
            }
            String dstGid = idMap.getOrDefault(srcGid, toDstGroupId(srcGid, str(g.get("type"))));
            if (!dstGids.contains(dstGid)) {
                continue;
            }
            Map<String, Object> info = dst.getGroupInfo(dstGid);
            String srcOwner = str(g.get("ownerAccount"));
            String dstOwner = info == null ? null : str(info.get("Owner_Account"));
            if (srcOwner == null || !srcOwner.equals(dstOwner)) {
                ownerMismatch++;
                if (ownerFail.size() < 20) {
                    ownerFail.add(srcGid + "->" + dstGid + " src=" + srcOwner + " dst=" + dstOwner);
                }
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> srcMembers = (List<Map<String, Object>>) g.get("members");
            Set<String> srcSet = new HashSet<>();
            Set<String> srcAdmins = new HashSet<>();
            if (srcMembers != null) {
                for (Map<String, Object> m : srcMembers) {
                    String uid = str(m.get("Member_Account"));
                    if (uid == null) {
                        continue;
                    }
                    srcSet.add(uid);
                    if ("Admin".equalsIgnoreCase(str(m.get("Role")))) {
                        srcAdmins.add(uid);
                    }
                }
            }
            List<Map<String, Object>> dstMembers = dst.listAllMembers(dstGid);
            Set<String> dstSet = new HashSet<>();
            Set<String> dstAdmins = new HashSet<>();
            for (Map<String, Object> m : dstMembers) {
                String uid = str(m.get("Member_Account"));
                if (uid == null) {
                    continue;
                }
                dstSet.add(uid);
                if ("Admin".equalsIgnoreCase(str(m.get("Role")))) {
                    dstAdmins.add(uid);
                }
            }
            if (!srcSet.equals(dstSet)) {
                memberMismatch++;
                if (memberFail.size() < 20) {
                    memberFail.add(srcGid + "->" + dstGid + " srcSize=" + srcSet.size() + " dstSize=" + dstSet.size());
                }
            }
            Set<String> srcAdminsCmp = new HashSet<>(srcAdmins);
            Set<String> dstAdminsCmp = new HashSet<>(dstAdmins);
            srcAdminsCmp.remove(srcOwner);
            dstAdminsCmp.remove(dstOwner);
            if (!srcAdminsCmp.equals(dstAdminsCmp)) {
                adminMismatch++;
            }
            throttle();
        }
        report.put("ownerMismatch", ownerMismatch);
        report.put("ownerMismatchSample", ownerFail);
        report.put("memberMismatch", memberMismatch);
        report.put("memberMismatchSample", memberFail);
        report.put("adminMismatch", adminMismatch);

        boolean pass = userFail.isEmpty()
            && profileMismatch == 0
            && !dstScan.truncated
            && missingGroups.isEmpty()
            && ownerMismatch == 0
            && memberMismatch == 0
            && adminMismatch == 0;
        report.put("pass", pass);
        jsonPretty.writeValue(VERIFY_REPORT.toFile(), report);
        System.out.println("verify report -> " + VERIFY_REPORT + " pass=" + pass);
        return pass ? 0 : 2;
    }

    // -------------------- helpers --------------------

    private List<Map<String, Object>> readJsonl(Path path) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.exists(path)) {
            return out;
        }
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = json.readValue(line, Map.class);
                out.add(row);
            }
        }
        return out;
    }

    private static Set<String> loadLines(Path path) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        if (!Files.exists(path)) {
            return out;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.isBlank()) {
                out.add(t);
            }
        }
        return out;
    }

    private static synchronized void appendCkpt(Path path, String id) throws IOException {
        if (id == null || id.isBlank()) {
            return;
        }
        Files.writeString(path, id + "\n", StandardCharsets.UTF_8,
            Files.exists(path)
                ? java.nio.file.StandardOpenOption.APPEND
                : java.nio.file.StandardOpenOption.CREATE);
    }

    private void throttle() {
        sleepQuiet(100L);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean imOk(Map<?, ?> raw) {
        return errorCode(raw) == 0;
    }

    private static int errorCode(Map<?, ?> raw) {
        if (raw == null) {
            return -1;
        }
        Object code = raw.get("ErrorCode");
        return code instanceof Number n ? n.intValue() : -1;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static long numLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private record Profile(String nick, String faceUrl) {}

    private record GroupScan(List<String> ids, int imTotal, boolean truncated) {}

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
        final String key;
        final TLSSigAPIv2 sigApi;

        ImEndpoint(int sdkAppId, String restBase, String admin, String key) {
            this.sdkAppId = sdkAppId;
            this.restBase = restBase.endsWith("/") ? restBase : restBase + "/";
            this.admin = admin;
            this.key = key;
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
            IOException lastIo = null;
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
            if (lastIo != null) {
                throw lastIo;
            }
            return post(path, body);
        }

        Map<?, ?> post(String path, Map<String, Object> body) throws IOException {
            String url = buildUrl(path);
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

        String buildUrl(String path) {
            String adminSig = sigApi.genUserSig(admin, 86400);
            long random = Integer.toUnsignedLong(rng.nextInt());
            return restBase + path
                + "?sdkappid=" + sdkAppId
                + "&identifier=" + admin
                + "&usersig=" + adminSig
                + "&random=" + random
                + "&contenttype=json";
        }

        GroupScan scanAllGroupIds(int maxIds) throws IOException {
            return scanAllGroupIds(maxIds, null);
        }

        GroupScan scanAllGroupIds(int maxIds, String groupType) throws IOException {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            int imTotal = 0;
            long next = 0L;
            boolean finished = false;
            while (out.size() < maxIds) {
                Map<String, Object> body = new LinkedHashMap<>();
                int pageLimit = Math.min(100, maxIds - out.size());
                body.put("Limit", pageLimit);
                body.put("Next", next);
                if (groupType != null && !groupType.isBlank()) {
                    body.put("GroupType", groupType.trim());
                }
                Map<?, ?> raw = postWithRetry("group_open_http_svc/get_appid_group_list", body);
                if (raw == null || !imOk(raw)) {
                    throw new IOException("get_appid_group_list failed resp=" + raw);
                }
                if (imTotal <= 0) {
                    imTotal = (int) Math.min(numLong(raw.get("TotalCount")), Integer.MAX_VALUE);
                }
                Object list = raw.get("GroupIdList");
                if (!(list instanceof List<?> arr) || arr.isEmpty()) {
                    finished = true;
                    break;
                }
                for (Object o : arr) {
                    String gid = null;
                    if (o instanceof Map<?, ?> m) {
                        Object v = m.get("GroupId");
                        if (v != null) {
                            gid = v.toString();
                        }
                    } else if (o != null) {
                        gid = o.toString();
                    }
                    if (gid != null && !gid.isBlank()) {
                        out.add(gid.trim());
                    }
                }
                long nextVal = numLong(raw.get("Next"));
                if (nextVal == 0L) {
                    finished = true;
                    break;
                }
                next = nextVal;
                sleepQuiet(100L);
            }
            boolean truncated = !finished || (out.size() >= maxIds && imTotal > out.size());
            return new GroupScan(new ArrayList<>(out), imTotal, truncated);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> getGroupInfo(String groupId) throws IOException {
            Map<?, ?> raw = postWithRetry("group_open_http_svc/get_group_info", Map.of(
                "GroupIdList", List.of(groupId)));
            if (raw == null || !imOk(raw)) {
                return null;
            }
            Object list = raw.get("GroupInfo");
            if (!(list instanceof List<?> arr) || arr.isEmpty()) {
                return null;
            }
            Object first = arr.get(0);
            if (!(first instanceof Map<?, ?> m)) {
                return null;
            }
            Object ec = m.get("ErrorCode");
            if (ec instanceof Number n && n.intValue() != 0) {
                return null;
            }
            return new LinkedHashMap<>((Map<String, Object>) m);
        }

        List<Map<String, Object>> listAllMembers(String groupId) throws IOException {
            boolean community = groupId != null && groupId.startsWith("@TGS#_");
            return listAllMembers(groupId, community);
        }

        List<Map<String, Object>> listAllMembers(String groupId, boolean community) throws IOException {
            List<Map<String, Object>> out = new ArrayList<>();
            if (!community) {
                int offset = 0;
                int page = 100;
                while (true) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("GroupId", groupId);
                    body.put("Limit", page);
                    body.put("Offset", offset);
                    body.put("MemberInfoFilter", List.of("Role", "JoinTime", "NameCard", "MuteUntil"));
                    Map<?, ?> raw = postWithRetry("group_open_http_svc/get_group_member_info", body);
                    if (raw == null || !imOk(raw)) {
                        break;
                    }
                    Object list = raw.get("MemberList");
                    if (!(list instanceof List<?> arr) || arr.isEmpty()) {
                        break;
                    }
                    for (Object o : arr) {
                        if (o instanceof Map<?, ?> m) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("Member_Account", m.get("Member_Account"));
                            row.put("Role", m.get("Role"));
                            row.put("JoinTime", m.get("JoinTime"));
                            row.put("NameCard", m.get("NameCard"));
                            row.put("MuteUntil", m.get("MuteUntil"));
                            out.add(row);
                        }
                    }
                    if (arr.size() < page) {
                        break;
                    }
                    offset += page;
                    sleepQuiet(100L);
                }
                return out;
            }
            // 社群：用 Next 游标分页
            String next = "";
            int page = 100;
            int guard = 0;
            while (guard++ < 10_000) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("GroupId", groupId);
                body.put("Limit", page);
                body.put("Next", next == null ? "" : next);
                body.put("MemberInfoFilter", List.of("Role", "JoinTime", "NameCard", "MuteUntil"));
                Map<?, ?> raw = postWithRetry("group_open_http_svc/get_group_member_info", body);
                if (raw == null || !imOk(raw)) {
                    break;
                }
                Object list = raw.get("MemberList");
                if (!(list instanceof List<?> arr) || arr.isEmpty()) {
                    break;
                }
                for (Object o : arr) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("Member_Account", m.get("Member_Account"));
                        row.put("Role", m.get("Role"));
                        row.put("JoinTime", m.get("JoinTime"));
                        row.put("NameCard", m.get("NameCard"));
                        row.put("MuteUntil", m.get("MuteUntil"));
                        out.add(row);
                    }
                }
                Object nextObj = raw.get("Next");
                String nextVal = nextObj == null ? "" : String.valueOf(nextObj).trim();
                if (nextVal.isEmpty() || "0".equals(nextVal) || nextVal.equals(next)) {
                    break;
                }
                next = nextVal;
                sleepQuiet(100L);
            }
            return out;
        }

        List<String> listAllMemberIds(String groupId) throws IOException {
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> m : listAllMembers(groupId)) {
                String uid = str(m.get("Member_Account"));
                if (uid != null) {
                    ids.add(uid);
                }
            }
            return ids;
        }

        Map<String, Boolean> accountCheck(List<String> userIds) throws IOException {
            Map<String, Boolean> out = new HashMap<>();
            if (userIds.isEmpty()) {
                return out;
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (String uid : userIds) {
                items.add(Map.of("UserID", uid));
            }
            Map<?, ?> raw = postWithRetry("im_open_login_svc/account_check", Map.of("CheckItem", items));
            if (raw == null || !imOk(raw)) {
                for (String uid : userIds) {
                    out.put(uid, false);
                }
                return out;
            }
            Object list = raw.get("ResultItem");
            if (list instanceof List<?> arr) {
                for (Object o : arr) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    String uid = str(m.get("UserID"));
                    if (uid == null) {
                        continue;
                    }
                    Object status = m.get("AccountStatus");
                    boolean imported = status instanceof String s && "Imported".equalsIgnoreCase(s.trim());
                    if (!imported) {
                        Object exist = m.get("Exist");
                        imported = exist instanceof Number n && n.intValue() == 1;
                    }
                    out.put(uid, imported);
                }
            }
            for (String uid : userIds) {
                out.putIfAbsent(uid, false);
            }
            return out;
        }

        Map<String, Profile> getPortraits(List<String> userIds) throws IOException {
            Map<String, Profile> out = new HashMap<>();
            if (userIds.isEmpty()) {
                return out;
            }
            Map<?, ?> raw = postWithRetry("profile/portrait_get", Map.of(
                "To_Account", userIds,
                "TagList", List.of("Tag_Profile_IM_Nick", "Tag_Profile_IM_Image")));
            if (raw == null || !imOk(raw)) {
                for (String uid : userIds) {
                    out.put(uid, new Profile("", ""));
                }
                return out;
            }
            Object list = raw.get("UserProfileItem");
            if (list instanceof List<?> arr) {
                for (Object o : arr) {
                    if (!(o instanceof Map<?, ?> m)) {
                        continue;
                    }
                    String uid = str(m.get("To_Account"));
                    if (uid == null) {
                        continue;
                    }
                    String nick = "";
                    String face = "";
                    Object profiles = m.get("ProfileItem");
                    if (profiles instanceof List<?> parr) {
                        for (Object p : parr) {
                            if (!(p instanceof Map<?, ?> pm)) {
                                continue;
                            }
                            String tag = str(pm.get("Tag"));
                            String val = pm.get("Value") == null ? "" : String.valueOf(pm.get("Value"));
                            if ("Tag_Profile_IM_Nick".equals(tag)) {
                                nick = val;
                            } else if ("Tag_Profile_IM_Image".equals(tag)) {
                                face = val;
                            }
                        }
                    }
                    out.put(uid, new Profile(nick, face));
                }
            }
            for (String uid : userIds) {
                out.putIfAbsent(uid, new Profile("", ""));
            }
            return out;
        }
    }
}
