import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentyun.TLSSigAPIv2;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 清理腾讯 IM 测试账号与群组，保留 99Chat / 99Messenger。
 * 用法：java -cp "tls-sig-api-v2.jar:server.jar" ImProductionCleanup
 */
public class ImProductionCleanup {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String REST_BASE = "https://adminapisgp.im.qcloud.com/v4/";
    private static final String ADMIN = "administrator";
    private static final List<String> KEEP = List.of("99Chat", "99Messenger", "administrator");

    private final int sdkAppId;
    private final TLSSigAPIv2 sigApi;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build();
    private final ObjectMapper json = new ObjectMapper();
    private final Random rng = new Random();

    ImProductionCleanup(int sdkAppId, String key) {
        this.sdkAppId = sdkAppId;
        this.sigApi = new TLSSigAPIv2(sdkAppId, key);
    }

    public static void main(String[] args) throws Exception {
        String host = env("DB_HOST", "127.0.0.1");
        String port = env("DB_PORT", "3306");
        String db = env("DB_NAME", "chat99");
        String user = env("DB_USERNAME", "chat99");
        String pass = env("DB_PASSWORD", "chat99");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
            + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";

        int sdkAppId = 0;
        String imKey = null;
        List<String> deleteUsers = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                "SELECT setting_value FROM app_setting WHERE setting_key='IM_SDK_APP_ID'")) {
                if (rs.next()) {
                    sdkAppId = Integer.parseInt(rs.getString(1).trim());
                }
            }
            try (ResultSet rs = st.executeQuery(
                "SELECT setting_value FROM app_setting WHERE setting_key='IM_KEY'")) {
                if (rs.next()) {
                    imKey = rs.getString(1).trim();
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT user_id FROM users")) {
                while (rs.next()) {
                    String uid = rs.getString(1);
                    if (!KEEP.contains(uid)) {
                        deleteUsers.add(uid);
                    }
                }
            }
        }

        if (sdkAppId == 0 || imKey == null || imKey.isBlank()) {
            System.err.println("IM not configured, skip IM cleanup");
            return;
        }

        ImProductionCleanup cleaner = new ImProductionCleanup(sdkAppId, imKey);
        System.out.println("Destroying IM groups...");
        List<String> groupIds = cleaner.scanAllGroupIds(5000);
        System.out.println("Found groups: " + groupIds.size());
        for (String gid : groupIds) {
            cleaner.destroyGroup(gid);
        }

        System.out.println("Deleting IM accounts from file/users list: " + deleteUsers.size());
        for (int i = 0; i < deleteUsers.size(); i += 100) {
            List<String> batch = deleteUsers.subList(i, Math.min(i + 100, deleteUsers.size()));
            cleaner.deleteAccounts(batch);
        }

        // 若 users 表已清空，仍尝试从导出文件删除
        Path export = Path.of("scripts/im-users-to-delete.txt");
        if (Files.exists(export)) {
            List<String> exported = Files.readAllLines(export, StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(s -> !s.isBlank() && !KEEP.contains(s)).distinct().toList();
            System.out.println("Extra delete from export file: " + exported.size());
            for (int i = 0; i < exported.size(); i += 100) {
                List<String> batch = exported.subList(i, Math.min(i + 100, exported.size()));
                cleaner.deleteAccounts(batch);
            }
        }

        System.out.println("IM cleanup done.");
    }

    private List<String> scanAllGroupIds(int max) throws IOException {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        long next = 0L;
        while (out.size() < max) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Limit", Math.min(100, max - out.size()));
            body.put("Next", next);
            Map<?, ?> raw = post("group_open_http_svc/get_appid_group_list", body);
            if (raw == null || !imOk(raw)) {
                break;
            }
            Object list = raw.get("GroupIdList");
            if (!(list instanceof List<?> arr) || arr.isEmpty()) {
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
                break;
            }
            next = nextVal;
        }
        return new java.util.ArrayList<>(out);
    }

    private void destroyGroup(String groupId) throws IOException {
        Map<?, ?> raw = post("group_open_http_svc/destroy_group", Map.of("GroupId", groupId));
        if (raw != null && imOk(raw)) {
            System.out.println("  destroyed group " + groupId);
        } else {
            System.err.println("  destroy group failed " + groupId + " resp=" + raw);
        }
    }

    private void deleteAccounts(List<String> userIds) throws IOException {
        for (String uid : userIds) {
            if (uid == null || uid.isBlank() || KEEP.contains(uid.trim())) {
                continue;
            }
            Map<String, Object> body = Map.of("DeleteItem", List.of(Map.of("UserID", uid.trim())));
            Map<?, ?> raw = post("im_open_login_svc/account_delete", body);
            if (raw != null && imOk(raw)) {
                System.out.println("  deleted account " + uid);
            } else {
                System.err.println("  delete account failed " + uid + " resp=" + raw);
            }
        }
    }

    private Map<?, ?> post(String path, Map<String, Object> body) throws IOException {
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
            return json.readValue(resp.body().string(), Map.class);
        }
    }

    private String buildUrl(String path) {
        String adminSig = sigApi.genUserSig(ADMIN, 86400);
        long random = (long) rng.nextInt(Integer.MAX_VALUE);
        return REST_BASE + path
            + "?sdkappid=" + sdkAppId
            + "&identifier=" + ADMIN
            + "&usersig=" + adminSig
            + "&random=" + random
            + "&contenttype=json";
    }

    private static boolean imOk(Map<?, ?> raw) {
        Object code = raw.get("ErrorCode");
        int err = code instanceof Number n ? n.intValue() : 0;
        return err == 0;
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
}
