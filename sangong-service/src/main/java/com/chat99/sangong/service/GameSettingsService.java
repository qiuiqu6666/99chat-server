package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.chat99.sangong.repository.SettingsRepository;
import com.chat99.sangong.repository.TenantRepository;
import com.chat99.sangong.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 游戏规则设置：与 PHP GameSettingsService 键名、默认值、迁移逻辑一致。 */
@Service
public class GameSettingsService {
    public static final String KEY_DOOR_COUNT = "door_count";
    public static final String KEY_MIN_BET = "min_bet";
    public static final String KEY_MAX_BET = "max_bet";
    public static final long MAX_BET = 99999999L;
    public static final String KEY_ODDS_PAIR = "odds_pair";
    public static final String KEY_ODDS_MAX = "odds_max";
    public static final String KEY_RAKE_BANKER_PAIR = "rake_banker_pair";
    public static final String KEY_RAKE_PLAYER_PAIR = "rake_player_pair";
    public static final String KEY_RAKE_BANKER_MAX = "rake_banker_max";
    public static final String KEY_RAKE_PLAYER_MAX = "rake_player_max";
    public static final String KEY_IM_GROUP_GAME_ID = "im_group_game_id";
    public static final String KEY_IM_GROUP_ADMIN_STATS_ID = "im_group_admin_stats_id";
    public static final String KEY_IM_BOT_USER_ID = "im_bot_user_id";
    public static final String KEY_ODDS_POINT_LEGACY = "odds_point";
    public static final String KEY_RAKE_POINT_LEGACY = "rake_point";
    public static final String KEY_RAKE_PAIR_LEGACY = "rake_pair";
    public static final String KEY_RAKE_MAX_LEGACY = "rake_max";
    public static final int POINT_MIN = 0;
    public static final int POINT_MAX = 9;

    private final SettingsRepository repo;
    private final TenantRepository tenants;
    private final SangongProperties props;
    private final ConcurrentHashMap<String, Map<String, String>> cacheByTenant = new ConcurrentHashMap<>();

    public GameSettingsService(SettingsRepository repo, TenantRepository tenants, SangongProperties props) {
        this.repo = repo;
        this.tenants = tenants;
        this.props = props;
    }

    public static String oddsPointKey(int p) { return "odds_p" + p; }
    public static String rakeBankerPointKey(int p) { return "rake_banker_p" + p; }
    public static String rakePlayerPointKey(int p) { return "rake_player_p" + p; }
    public static String rakePointLegacyKey(int p) { return "rake_p" + p; }

    public Map<String, String> defaults() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(KEY_DOOR_COUNT, "6");
        out.put(KEY_MIN_BET, "0");
        out.put(KEY_MAX_BET, String.valueOf(MAX_BET));
        out.put(KEY_ODDS_PAIR, "100");
        out.put(KEY_ODDS_MAX, "100");
        out.put(KEY_RAKE_BANKER_PAIR, "6");
        out.put(KEY_RAKE_PLAYER_PAIR, "0");
        out.put(KEY_RAKE_BANKER_MAX, "6");
        out.put(KEY_RAKE_PLAYER_MAX, "0");
        out.put(KEY_IM_GROUP_GAME_ID, nz(props.getIm().getGroupGameId()));
        out.put(KEY_IM_GROUP_ADMIN_STATS_ID, nz(props.getIm().getGroupAdminStatsId()));
        out.put(KEY_IM_BOT_USER_ID, nz(props.getIm().getBotUserId()));
        for (int p = POINT_MIN; p <= POINT_MAX; p++) {
            out.put(oddsPointKey(p), "100");
            out.put(rakeBankerPointKey(p), "6");
            out.put(rakePlayerPointKey(p), "0");
        }
        return out;
    }

    public Map<String, Object> all() {
        return toApi(raw());
    }

    public int getDoorCount() {
        return Integer.parseInt(raw().get(KEY_DOOR_COUNT));
    }

    public long getMinBet() {
        return Long.parseLong(raw().get(KEY_MIN_BET));
    }

    public long getMaxBet() {
        return Long.parseLong(raw().get(KEY_MAX_BET));
    }

    /** 庄家抽水比例（%），基数为本局闲家下注总额（取 0 点庄抽水）。 */
    public int getBankerRakePercent() {
        return Integer.parseInt(raw().get(rakeBankerPointKey(0)));
    }

    public String validateBetAmount(long amount) {
        long min = getMinBet();
        long max = getMaxBet();
        if (min > 0 && amount < min) {
            return "单注最小 " + min;
        }
        if (max > 0 && amount > max) {
            return "单注最大 " + max;
        }
        return null;
    }

    public String validateDoor(int door) {
        int max = getDoorCount();
        if (door < 1 || door > max) {
            return "门号须为 1-" + max;
        }
        return null;
    }

    public String getImGroupGameId() {
        return resolveImId(KEY_IM_GROUP_GAME_ID, props.getIm().getGroupGameId());
    }

    public String getImGroupAdminStatsId() {
        var t = tenants.findById(TenantContext.require());
        if (t.isPresent() && t.get().getImGroupAdminStatsId() != null && !t.get().getImGroupAdminStatsId().isBlank()) {
            return t.get().getImGroupAdminStatsId().trim();
        }
        return resolveImId(KEY_IM_GROUP_ADMIN_STATS_ID, props.getIm().getGroupAdminStatsId());
    }

    public String getImGroupLedgerId() {
        var t = tenants.findById(TenantContext.require());
        if (t.isPresent() && t.get().getImGroupLedgerId() != null && !t.get().getImGroupLedgerId().isBlank()) {
            return t.get().getImGroupLedgerId().trim();
        }
        return nz(props.getIm().getGroupLedgerId()).trim();
    }

    public String getImBotUserId() {
        var t = tenants.findById(TenantContext.require());
        if (t.isPresent() && t.get().getImBotUserId() != null && !t.get().getImBotUserId().isBlank()) {
            return normalizeBotUserId(t.get().getImBotUserId());
        }
        return normalizeBotUserId(resolveImId(KEY_IM_BOT_USER_ID, props.getIm().getBotUserId()));
    }

    public synchronized Map<String, Object> update(Map<String, Object> input) {
        Map<String, String> normalized = normalizeInput(input);
        for (Map.Entry<String, String> e : normalized.entrySet()) {
            repo.upsert(e.getKey(), e.getValue());
        }
        invalidateCache();
        return all();
    }

    public void invalidateCache() {
        // 显式 require：ctx 缺失时不能再 fallback 到清空所有租户的缓存。
        // 之前的设计是 null 时 cacheByTenant.clear()（全局雪崩），现在改成 throw 让上游响亮失败。
        String tenantId = TenantContext.require();
        cacheByTenant.remove(tenantId);
    }

    public void invalidateCache(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            cacheByTenant.remove(tenantId);
        }
    }

    private synchronized Map<String, String> raw() {
        String tenantId = TenantContext.require();
        Map<String, String> current = cacheByTenant.get(tenantId);
        if (current != null) {
            return current;
        }
        Map<String, String> merged = new LinkedHashMap<>(defaults());
        Map<String, String> rows = repo.loadAll(tenantId);
        merged.putAll(rows);
        migrateLegacyPointSettings(merged, rows);
        migrateLegacyRakeSettings(merged, rows);
        ensureDefaults(merged, rows);
        cacheByTenant.put(tenantId, merged);
        return merged;
    }

    private void migrateLegacyPointSettings(Map<String, String> merged, Map<String, String> rows) {
        boolean hasNew = rows.containsKey(oddsPointKey(0));
        String legacyOdds = rows.get(KEY_ODDS_POINT_LEGACY);
        String legacyRake = rows.get(KEY_RAKE_POINT_LEGACY);
        if (!hasNew && (legacyOdds != null || legacyRake != null)) {
            String odds = legacyOdds != null ? legacyOdds : "100";
            String rake = legacyRake != null ? legacyRake : "6";
            for (int p = POINT_MIN; p <= POINT_MAX; p++) {
                merged.put(oddsPointKey(p), odds);
                merged.put(rakeBankerPointKey(p), rake);
                merged.putIfAbsent(rakePlayerPointKey(p), "0");
            }
        }
    }

    private void migrateLegacyRakeSettings(Map<String, String> merged, Map<String, String> rows) {
        for (int p = POINT_MIN; p <= POINT_MAX; p++) {
            String legacy = rows.get(rakePointLegacyKey(p));
            if (legacy != null && !rows.containsKey(rakeBankerPointKey(p))) {
                merged.put(rakeBankerPointKey(p), legacy);
            }
            merged.putIfAbsent(rakePlayerPointKey(p), "0");
        }
        String legacyPair = rows.get(KEY_RAKE_PAIR_LEGACY);
        if (legacyPair != null && !rows.containsKey(KEY_RAKE_BANKER_PAIR)) {
            merged.put(KEY_RAKE_BANKER_PAIR, legacyPair);
        }
        merged.putIfAbsent(KEY_RAKE_PLAYER_PAIR, "0");
        String legacyMax = rows.get(KEY_RAKE_MAX_LEGACY);
        if (legacyMax != null && !rows.containsKey(KEY_RAKE_BANKER_MAX)) {
            merged.put(KEY_RAKE_BANKER_MAX, legacyMax);
        }
        merged.putIfAbsent(KEY_RAKE_PLAYER_MAX, "0");
    }

    private void ensureDefaults(Map<String, String> merged, Map<String, String> rows) {
        for (Map.Entry<String, String> e : defaults().entrySet()) {
            if (!rows.containsKey(e.getKey())) {
                repo.insertIfAbsent(e.getKey(), merged.getOrDefault(e.getKey(), e.getValue()));
            }
        }
    }

    private Map<String, Object> toApi(Map<String, String> raw) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int p = POINT_MIN; p <= POINT_MAX; p++) {
            points.add(handTypeToApi(p + "点", raw.get(oddsPointKey(p)),
                raw.get(rakeBankerPointKey(p)), raw.get(rakePlayerPointKey(p)), p));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doorCount", Integer.parseInt(raw.get(KEY_DOOR_COUNT)));
        out.put("minBet", Long.parseLong(raw.get(KEY_MIN_BET)));
        out.put("maxBet", Long.parseLong(raw.get(KEY_MAX_BET)));
        out.put("points", points);
        out.put("pair", handTypeToApi("对子", raw.get(KEY_ODDS_PAIR),
            raw.get(KEY_RAKE_BANKER_PAIR), raw.get(KEY_RAKE_PLAYER_PAIR), null));
        out.put("maxHand", handTypeToApi("1.00", raw.get(KEY_ODDS_MAX),
            raw.get(KEY_RAKE_BANKER_MAX), raw.get(KEY_RAKE_PLAYER_MAX), null));
        out.put("imGroupGameId", getImGroupGameId());
        out.put("imGroupAdminStatsId", getImGroupAdminStatsId());
        out.put("imBotUserId", getImBotUserId());
        return out;
    }

    private Map<String, Object> handTypeToApi(String label, String odds, String bankerRake,
                                              String playerRake, Integer point) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("odds", oddsToFloat(odds));
        item.put("bankerRakePoints", Integer.parseInt(bankerRake));
        item.put("playerRakePoints", Integer.parseInt(playerRake));
        if (point != null) {
            item.put("point", point);
        }
        return item;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> normalizeInput(Map<String, Object> input) {
        Map<String, String> out = new LinkedHashMap<>();

        if (input.containsKey("doorCount")) {
            int door = toInt(input.get("doorCount"));
            if (door < 2 || door > 10) {
                throw new RuntimeException("门数须为 2-10");
            }
            out.put(KEY_DOOR_COUNT, String.valueOf(door));
        }
        if (input.containsKey("minBet")) {
            long min = toLong(input.get("minBet"));
            if (min < 0) throw new RuntimeException("最小下注不能为负");
            if (min > MAX_BET) throw new RuntimeException("最小下注不能超过 " + MAX_BET);
            out.put(KEY_MIN_BET, String.valueOf(min));
        }
        if (input.containsKey("maxBet")) {
            long max = toLong(input.get("maxBet"));
            if (max < 0) throw new RuntimeException("最大下注不能为负");
            if (max > MAX_BET) throw new RuntimeException("最大下注不能超过 " + MAX_BET);
            out.put(KEY_MAX_BET, String.valueOf(max));
        }
        if (input.get("points") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> item)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                if (!m.containsKey("point")) {
                    throw new RuntimeException("points 每项须包含 point(0-9)");
                }
                int p = toInt(m.get("point"));
                assertPoint(p);
                applyOddsRake(out, oddsPointKey(p), rakeBankerPointKey(p), rakePlayerPointKey(p), m, p + "点");
            }
        }
        if (input.get("pair") instanceof Map<?, ?> pair) {
            applyOddsRake(out, KEY_ODDS_PAIR, KEY_RAKE_BANKER_PAIR, KEY_RAKE_PLAYER_PAIR,
                (Map<String, Object>) pair, "对子");
        }
        if (input.get("maxHand") instanceof Map<?, ?> maxHand) {
            applyOddsRake(out, KEY_ODDS_MAX, KEY_RAKE_BANKER_MAX, KEY_RAKE_PLAYER_MAX,
                (Map<String, Object>) maxHand, "1.00");
        }
        applyImIdInput(out, input, "imGroupGameId", "IM_GROUP_GAME_ID", KEY_IM_GROUP_GAME_ID, "游戏群");
        applyImIdInput(out, input, "imGroupAdminStatsId", "IM_GROUP_ADMIN_STATS_ID", KEY_IM_GROUP_ADMIN_STATS_ID, "管理员统计群");
        applyImIdInput(out, input, "imBotUserId", "IM_BOT_USER_ID", KEY_IM_BOT_USER_ID, "机器人");

        if (input.get("handTypes") instanceof Map<?, ?> ht) {
            Map<String, Object> handTypes = (Map<String, Object>) ht;
            if (handTypes.get("pair") instanceof Map<?, ?> p) {
                applyOddsRake(out, KEY_ODDS_PAIR, KEY_RAKE_BANKER_PAIR, KEY_RAKE_PLAYER_PAIR,
                    (Map<String, Object>) p, "对子");
            }
            if (handTypes.get("max") instanceof Map<?, ?> mx) {
                applyOddsRake(out, KEY_ODDS_MAX, KEY_RAKE_BANKER_MAX, KEY_RAKE_PLAYER_MAX,
                    (Map<String, Object>) mx, "1.00");
            }
            if (handTypes.get("point") instanceof Map<?, ?> pt) {
                for (int p = POINT_MIN; p <= POINT_MAX; p++) {
                    applyOddsRake(out, oddsPointKey(p), rakeBankerPointKey(p), rakePlayerPointKey(p),
                        (Map<String, Object>) pt, "点数");
                }
            }
        }

        Map<String, String> current = TenantContext.get() != null ? raw() : defaults();
        long min = out.containsKey(KEY_MIN_BET) ? Long.parseLong(out.get(KEY_MIN_BET))
            : Long.parseLong(current.getOrDefault(KEY_MIN_BET, "0"));
        long max = out.containsKey(KEY_MAX_BET) ? Long.parseLong(out.get(KEY_MAX_BET))
            : Long.parseLong(current.getOrDefault(KEY_MAX_BET, "0"));
        if (max > 0 && min > max) {
            throw new RuntimeException("最小下注不能大于最大下注");
        }
        return out;
    }

    private void applyImIdInput(Map<String, String> out, Map<String, Object> input,
                                String camelKey, String upperKey, String settingKey, String label) {
        Object value = input.containsKey(camelKey) ? input.get(camelKey)
            : (input.containsKey(upperKey) ? input.get(upperKey) : null);
        if (value == null && !input.containsKey(camelKey) && !input.containsKey(upperKey)) {
            return;
        }
        String id = value == null ? "" : String.valueOf(value).trim();
        if (KEY_IM_BOT_USER_ID.equals(settingKey)) {
            id = normalizeBotUserId(id);
        }
        if (id.length() > 128) {
            throw new RuntimeException(label + " ID 过长");
        }
        out.put(settingKey, id);
    }

    /**
     * 机器人是 IM 用户号，不能带 {@code @}（{@code @} 是群 ID 前缀）。
     * 前端常误填 {@code @userId}，会导致腾讯 IM 返回 From_Account invalid。
     */
    public static String normalizeBotUserId(String raw) {
        if (raw == null) {
            return "";
        }
        String id = raw.trim();
        while (id.startsWith("@") && !id.startsWith("@TGS")) {
            id = id.substring(1).trim();
        }
        return id;
    }

    private void applyOddsRake(Map<String, String> out, String oddsKey, String bankerRakeKey,
                               String playerRakeKey, Map<String, Object> item, String label) {
        if (item.containsKey("odds")) {
            out.put(oddsKey, String.valueOf(oddsToStorage(item.get("odds"))));
        }
        Integer bankerRake = null;
        if (item.containsKey("bankerRakePoints")) {
            bankerRake = toInt(item.get("bankerRakePoints"));
        } else if (item.containsKey("rakePoints")) {
            bankerRake = toInt(item.get("rakePoints"));
        }
        if (bankerRake != null) {
            assertRakePoints(bankerRake, label + " 庄抽水");
            out.put(bankerRakeKey, String.valueOf(bankerRake));
        }
        if (item.containsKey("playerRakePoints")) {
            int playerRake = toInt(item.get("playerRakePoints"));
            assertRakePoints(playerRake, label + " 闲抽水");
            out.put(playerRakeKey, String.valueOf(playerRake));
        }
    }

    private void assertRakePoints(int rake, String label) {
        if (rake < 0 || rake > 100) {
            throw new RuntimeException(label + "须在 0-100 点之间");
        }
    }

    private void assertPoint(int point) {
        if (point < POINT_MIN || point > POINT_MAX) {
            throw new RuntimeException("点数须为 0-9");
        }
    }

    private int oddsToStorage(Object odds) {
        double value = toDouble(odds);
        if (value < 0.01 || value > 100) {
            throw new RuntimeException("赔率须在 0.01-100 之间");
        }
        return (int) Math.round(value * 100);
    }

    private double oddsToFloat(String stored) {
        return Math.round(Integer.parseInt(stored) / 100.0 * 100.0) / 100.0;
    }

    private String resolveImId(String settingKey, String configFallback) {
        String id = raw().getOrDefault(settingKey, "").trim();
        if (!id.isEmpty()) {
            return id;
        }
        return nz(configFallback);
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static int toInt(Object o) { return (int) toLong(o); }
    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return (long) Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
