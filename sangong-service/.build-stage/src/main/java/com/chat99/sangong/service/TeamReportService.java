package com.chat99.sangong.service;

import com.chat99.sangong.tenant.TenantContext;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 当前用户下级团队报表，所有查询均按租户和用户树隔离。 */
@Service
public class TeamReportService {
    private final NamedParameterJdbcTemplate jdbc;
    public TeamReportService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> summary(long userId, boolean direct, LocalDate from, LocalDate to) {
        String t = TenantContext.require();
        String scope = direct ? "h.parent_user_id=:u" : "h.path LIKE CONCAT('%/',:u,'/%') AND h.user_id<>:u";
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", t).addValue("u", userId)
            .addValue("from", from).addValue("to", to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true); out.put("tenantId", t); out.put("userId", userId);
        out.put("from", from); out.put("to", to); out.put("direct", direct);
        out.put("members", jdbc.queryForObject("SELECT COUNT(*) FROM sangong_user_hierarchy h WHERE h.tenant_id=:t AND " + scope,
            p, Long.class));
        out.put("totalBalance", jdbc.queryForObject("SELECT COALESCE(SUM(u.balance),0) FROM sangong_user_hierarchy h JOIN sangong_users u ON u.id=h.user_id WHERE h.tenant_id=:t AND " + scope,
            p, Long.class));
        out.put("playerTurnover", turnover(scope, p, "player_turnover"));
        out.put("bankerTurnover", turnover(scope, p, "banker_turnover"));
        out.put("totalTurnover", turnover(scope, p, "total_turnover"));
        out.put("totalRebate", ledger(scope, p, "amount"));
        out.put("totalUp", transferSum(scope, p, true));
        out.put("totalDown", transferSum(scope, p, false));
        out.put("totalProfitLoss", settlementNet(scope, p));
        return out;
    }

    public List<Map<String, Object>> members(long userId, boolean direct) {
        String t = TenantContext.require();
        String scope = direct ? "h.parent_user_id=:u" : "h.path LIKE CONCAT('%/',:u,'/%') AND h.user_id<>:u";
        return jdbc.queryForList("SELECT h.user_id AS userId,h.parent_user_id AS parentUserId,h.level_no AS levelNo," +
            "u.im_user_id AS imUserId,u.nickname,u.balance,COALESCE(SUM(rt.player_turnover),0) AS playerTurnover," +
            "COALESCE(SUM(rt.banker_turnover),0) AS bankerTurnover,COALESCE(SUM(rt.total_turnover),0) AS totalTurnover " +
            "FROM sangong_user_hierarchy h JOIN sangong_users u ON u.id=h.user_id " +
            "LEFT JOIN sangong_rebate_turnover rt ON rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id " +
            "WHERE h.tenant_id=:t AND " + scope + " GROUP BY h.user_id,h.parent_user_id,h.level_no,u.im_user_id,u.nickname,u.balance " +
            "ORDER BY h.level_no,h.user_id", new MapSqlParameterSource().addValue("t", t).addValue("u", userId));
    }

    public List<Map<String, Object>> adminHierarchy(Long rootUserId, LocalDate date) {
        String t = TenantContext.require();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", t).addValue("u", rootUserId)
            .addValue("d", date);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT h.user_id AS userId,h.parent_user_id AS parentUserId,h.level_no AS levelNo, " +
            "u.im_user_id AS imUserId,u.nickname,u.balance, " +
            "COALESCE((SELECT SUM(rt.player_turnover) FROM sangong_rebate_turnover rt " +
            "WHERE rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id AND rt.stat_date=:d),0) AS playerTurnover, " +
            "COALESCE((SELECT SUM(rt.banker_turnover) FROM sangong_rebate_turnover rt " +
            "WHERE rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id AND rt.stat_date=:d),0) AS bankerTurnover, " +
            "COALESCE((SELECT SUM(rt.total_turnover) FROM sangong_rebate_turnover rt " +
            "WHERE rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id AND rt.stat_date=:d),0) AS todayTotalTurnover, " +
            "COALESCE((SELECT SUM(CASE WHEN l.type IN ('admin_credit','credit','user_transfer_in') THEN ABS(l.amount) ELSE 0 END) " +
            "FROM sangong_ledger l WHERE l.tenant_id=h.tenant_id AND l.user_id=h.user_id AND DATE(l.created_at)=:d),0) AS todayUp, " +
            "COALESCE((SELECT SUM(CASE WHEN l.type IN ('admin_debit','debit','user_transfer_out') THEN ABS(l.amount) ELSE 0 END) " +
            "FROM sangong_ledger l WHERE l.tenant_id=h.tenant_id AND l.user_id=h.user_id AND DATE(l.created_at)=:d),0) AS todayDown, " +
            "COALESCE((SELECT SUM(CASE WHEN l.type IN ('settle_win','settle_banker','settle_void','settle_banker_void') THEN l.amount ELSE 0 END) " +
            "FROM sangong_ledger l WHERE l.tenant_id=h.tenant_id AND l.user_id=h.user_id AND DATE(l.created_at)=:d),0) AS todayProfitLoss " +
            "FROM sangong_user_hierarchy h JOIN sangong_users u ON u.tenant_id=h.tenant_id AND u.id=h.user_id " +
            "WHERE h.tenant_id=:t AND h.is_active=1 AND (:u IS NULL OR h.user_id=:u OR h.path LIKE CONCAT('%/',:u,'/%')) " +
            "ORDER BY h.level_no,h.user_id", p);
        for (Map<String, Object> row : rows) {
            long banker = ((Number) row.getOrDefault("bankerTurnover", 0)).longValue();
            long player = ((Number) row.getOrDefault("playerTurnover", 0)).longValue();
            row.put("todayTotalTurnover", banker + player);
        }
        return rows;
    }

    /** 经营日报按一次开机到关机的业务批次汇总，不受自然日跨零点影响。 */
    public List<Map<String, Object>> adminHierarchyBySession(Long rootUserId, long sessionId) {
        String t = TenantContext.require();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", t).addValue("u", rootUserId)
            .addValue("s", sessionId);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT h.user_id AS userId,h.parent_user_id AS parentUserId,h.level_no AS levelNo, " +
            "u.im_user_id AS imUserId,u.nickname,u.balance,u.player_rebate_pct AS rebatePct, " +
            "COALESCE((SELECT SUM(rt.player_turnover) FROM sangong_rebate_turnover rt " +
            "WHERE rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id AND rt.session_id=:s),0) AS playerTurnover, " +
            "COALESCE((SELECT SUM(rt.banker_turnover) FROM sangong_rebate_turnover rt " +
            "WHERE rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id AND rt.session_id=:s),0) AS bankerTurnover, " +
            "COALESCE((SELECT SUM(CASE WHEN l.type IN ('admin_credit','credit','user_transfer_in') THEN ABS(l.amount) ELSE 0 END) " +
            "FROM sangong_ledger l WHERE l.tenant_id=h.tenant_id AND l.user_id=h.user_id AND l.session_id=:s),0) AS todayUp, " +
            "COALESCE((SELECT SUM(CASE WHEN l.type IN ('admin_debit','debit','user_transfer_out') THEN ABS(l.amount) ELSE 0 END) " +
            "FROM sangong_ledger l WHERE l.tenant_id=h.tenant_id AND l.user_id=h.user_id AND l.session_id=:s),0) AS todayDown, " +
            "COALESCE((SELECT SUM(CASE WHEN l.type IN ('settle_win','settle_banker','settle_void','settle_banker_void') THEN l.amount ELSE 0 END) " +
            "FROM sangong_ledger l WHERE l.tenant_id=h.tenant_id AND l.user_id=h.user_id AND l.session_id=:s),0) AS todayProfitLoss, " +
            "COALESCE((SELECT SUM(CASE WHEN l.type IN ('rebate_player','rebate_agent_diff','rebate_player_void') THEN l.amount ELSE 0 END) " +
            "FROM sangong_ledger l WHERE l.tenant_id=h.tenant_id AND l.user_id=h.user_id AND l.session_id=:s),0) AS todayRebate " +
            "FROM sangong_user_hierarchy h JOIN sangong_users u ON u.tenant_id=h.tenant_id AND u.id=h.user_id " +
            "WHERE h.tenant_id=:t AND h.is_active=1 AND (:u IS NULL OR h.user_id=:u OR h.path LIKE CONCAT('%/',:u,'/%')) " +
            "ORDER BY h.level_no,h.user_id", p);
        for (Map<String, Object> row : rows) {
            long banker = ((Number) row.getOrDefault("bankerTurnover", 0)).longValue();
            long player = ((Number) row.getOrDefault("playerTurnover", 0)).longValue();
            row.put("todayTotalTurnover", banker + player);
            row.put("batchTotalTurnover", banker + player);
            row.put("batchUp", row.get("todayUp"));
            row.put("batchDown", row.get("todayDown"));
            row.put("batchProfitLoss", row.get("todayProfitLoss"));
            row.put("batchRebate", row.get("todayRebate"));
            double pct = ((Number) row.getOrDefault("rebatePct", 0)).doubleValue();
            row.put("rebatePer10000", Math.round(pct * 100.0d));
        }
        return rows;
    }

    /** 代理团队按一次开机到关机的业务批次汇总。 */
    public Map<String, Object> summaryBySession(long userId, boolean direct, long sessionId) {
        List<Map<String, Object>> members = membersBySession(userId, direct, sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("members", members.size());
        out.put("totalBalance", sum(members, "balance"));
        out.put("playerTurnover", sum(members, "playerTurnover"));
        out.put("bankerTurnover", sum(members, "bankerTurnover"));
        out.put("totalTurnover", sum(members, "batchTotalTurnover"));
        out.put("totalUp", sum(members, "batchUp"));
        out.put("totalDown", sum(members, "batchDown"));
        out.put("totalProfitLoss", sum(members, "batchProfitLoss"));
        out.put("totalRebate", sum(members, "batchRebate"));
        return out;
    }

    /** 代理团队成员按业务批次查询，不包含代理本人。 */
    public List<Map<String, Object>> membersBySession(long userId, boolean direct, long sessionId) {
        Integer agentLevel = jdbc.queryForObject(
            "SELECT level_no FROM sangong_user_hierarchy WHERE tenant_id=:t AND user_id=:u AND is_active=1",
            new MapSqlParameterSource().addValue("t", TenantContext.require()).addValue("u", userId), Integer.class);
        int baseLevel = agentLevel == null ? 0 : agentLevel;
        return adminHierarchyBySession(userId, sessionId).stream()
            .filter(row -> ((Number) row.get("userId")).longValue() != userId)
            .filter(row -> !direct || (row.get("parentUserId") instanceof Number parent
                && parent.longValue() == userId))
            .map(row -> {
                Map<String, Object> relative = new LinkedHashMap<>(row);
                Object rawLevel = row.get("levelNo");
                int level = rawLevel instanceof Number value ? value.intValue() : baseLevel + 1;
                relative.put("levelNo", Math.max(1, level - baseLevel));
                return relative;
            })
            .toList();
    }

    /** 判断目标用户是否属于代理的任意层级下级。 */
    public boolean isDescendant(long agentUserId, long targetUserId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM sangong_user_hierarchy
            WHERE tenant_id=:t AND user_id=:target AND is_active=1
              AND path LIKE CONCAT('%/', :agent, '/%') AND user_id<>:agent
            """, new MapSqlParameterSource().addValue("t", TenantContext.require())
                .addValue("agent", agentUserId).addValue("target", targetUserId), Integer.class);
        return count != null && count > 0;
    }

    /** 当前批次内最近一局已结算数据；无已结算局时所有金额为 0。 */
    public Map<String, Object> latestRoundStats(long userId, long sessionId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", TenantContext.require())
            .addValue("u", userId).addValue("s", sessionId);
        // 尚未结算过任何一局时查询会返回 0 行；此时面板应正常返回全 0，不能把它当作错误。
        List<Long> roundIds = jdbc.query("""
            SELECT id FROM sangong_rounds
            WHERE tenant_id=:t AND session_id=:s AND status='settled'
            ORDER BY id DESC LIMIT 1
            """, p, (rs, rowNum) -> rs.getLong("id"));
        Long roundId = roundIds.isEmpty() ? null : roundIds.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("latestRoundId", roundId);
        if (roundId == null) {
            out.put("latestProfitLoss", 0L); out.put("latestTotalTurnover", 0L);
            out.put("latestUp", 0L); out.put("latestDown", 0L); out.put("latestRebate", 0L);
            return out;
        }
        p.addValue("r", roundId);
        out.put("latestTotalTurnover", value("""
            SELECT COALESCE(SUM(amount),0) FROM sangong_rebate_turnover_events
            WHERE tenant_id=:t AND user_id=:u AND round_id=:r AND reversed_at IS NULL
            """, p));
        out.put("latestProfitLoss", value("""
            SELECT COALESCE(SUM(amount),0) FROM sangong_ledger
            WHERE tenant_id=:t AND user_id=:u AND ref_type='round' AND ref_id=:r
              AND type IN ('settle_win','settle_banker','settle_void','settle_banker_void')
            """, p));
        out.put("latestUp", value("""
            SELECT COALESCE(SUM(ABS(amount)),0) FROM sangong_ledger
            WHERE tenant_id=:t AND user_id=:u AND ref_type='round' AND ref_id=:r
              AND type IN ('admin_credit','credit','user_transfer_in')
            """, p));
        out.put("latestDown", value("""
            SELECT COALESCE(SUM(ABS(amount)),0) FROM sangong_ledger
            WHERE tenant_id=:t AND user_id=:u AND ref_type='round' AND ref_id=:r
              AND type IN ('admin_debit','debit','user_transfer_out')
            """, p));
        out.put("latestRebate", value("""
            SELECT COALESCE(SUM(amount),0) FROM sangong_rebate_ledger
            WHERE tenant_id=:t AND user_id=:u AND source_round_id=:r
            """, p));
        return out;
    }

    /** 用户按业务日期的累计数据；同一天多次开关机自动合并。 */
    public List<Map<String, Object>> memberDaily(long userId, java.time.LocalDate from, java.time.LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", TenantContext.require())
            .addValue("u", userId).addValue("from", from).addValue("to", to);
        List<Map<String, Object>> sessions = jdbc.queryForList("""
            SELECT s.business_date AS businessDate,
              COALESCE((SELECT l.balance_after FROM sangong_ledger l
                WHERE l.tenant_id=s.tenant_id AND l.user_id=:u
                  AND l.created_at < DATE_ADD(s.business_date, INTERVAL 1 DAY)
                ORDER BY l.id DESC LIMIT 1),0) AS balance,
              COALESCE((SELECT SUM(rt.total_turnover) FROM sangong_rebate_turnover rt
                WHERE rt.tenant_id=s.tenant_id AND rt.user_id=:u AND rt.session_id=s.id),0) AS totalTurnover,
              COALESCE((SELECT SUM(ABS(l.amount)) FROM sangong_ledger l
                WHERE l.tenant_id=s.tenant_id AND l.user_id=:u AND l.session_id=s.id
                  AND l.type IN ('admin_credit','credit','user_transfer_in')),0) AS totalUp,
              COALESCE((SELECT SUM(ABS(l.amount)) FROM sangong_ledger l
                WHERE l.tenant_id=s.tenant_id AND l.user_id=:u AND l.session_id=s.id
                  AND l.type IN ('admin_debit','debit','user_transfer_out')),0) AS totalDown,
              COALESCE((SELECT SUM(l.amount) FROM sangong_ledger l
                WHERE l.tenant_id=s.tenant_id AND l.user_id=:u AND l.session_id=s.id
                  AND l.type IN ('settle_win','settle_banker','settle_void','settle_banker_void')),0) AS profitLoss,
              COALESCE((SELECT SUM(rl.amount) FROM sangong_rebate_ledger rl
                WHERE rl.tenant_id=s.tenant_id AND rl.user_id=:u AND rl.session_id=s.id),0) AS rebate
            FROM sangong_sessions s
            WHERE s.tenant_id=:t AND s.business_date BETWEEN :from AND :to
            ORDER BY s.business_date DESC, s.id DESC
            """, p);
        Map<Object, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : sessions) {
            Object date = row.get("businessDate");
            Map<String, Object> total = byDate.computeIfAbsent(date, key -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("businessDate", key); item.put("balance", 0L); item.put("endOfDayBalance", 0L); item.put("totalTurnover", 0L); item.put("totalUp", 0L);
                item.put("totalDown", 0L); item.put("profitLoss", 0L); item.put("rebate", 0L); return item;
            });
            for (String key : List.of("totalTurnover", "totalUp", "totalDown", "profitLoss", "rebate")) {
                Object value = row.get(key);
                total.put(key, ((Number) total.get(key)).longValue()
                    + (value instanceof Number number ? number.longValue() : 0L));
            }
            // 同一天多次开关机时，所有行使用同一日末余额；不应进行累加。
            total.put("balance", row.get("balance") instanceof Number number ? number.longValue() : 0L);
            total.put("endOfDayBalance", total.get("balance"));
        }
        return new java.util.ArrayList<>(byDate.values());
    }

    /** 单个业务批次的个人数据；运行中余额取实时余额，已结束批次取关机时最后一笔账变余额。 */
    public Map<String, Object> memberBatch(long userId, long sessionId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", TenantContext.require())
            .addValue("u", userId).addValue("s", sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("balance", value("""
            SELECT COALESCE((SELECT l.balance_after FROM sangong_ledger l
              JOIN sangong_sessions s ON s.tenant_id=l.tenant_id AND s.id=:s
              WHERE l.tenant_id=:t AND l.user_id=:u
                AND l.created_at<=COALESCE(s.stopped_at,NOW())
              ORDER BY l.id DESC LIMIT 1), u.balance)
            FROM sangong_users u WHERE u.tenant_id=:t AND u.id=:u
            """, p));
        out.put("totalTurnover", value("""
            SELECT COALESCE(SUM(total_turnover),0) FROM sangong_rebate_turnover
            WHERE tenant_id=:t AND user_id=:u AND session_id=:s
            """, p));
        out.put("totalUp", value("""
            SELECT COALESCE(SUM(ABS(amount)),0) FROM sangong_ledger
            WHERE tenant_id=:t AND user_id=:u AND session_id=:s
              AND type IN ('admin_credit','credit','user_transfer_in')
            """, p));
        out.put("totalDown", value("""
            SELECT COALESCE(SUM(ABS(amount)),0) FROM sangong_ledger
            WHERE tenant_id=:t AND user_id=:u AND session_id=:s
              AND type IN ('admin_debit','debit','user_transfer_out')
            """, p));
        out.put("profitLoss", value("""
            SELECT COALESCE(SUM(amount),0) FROM sangong_ledger
            WHERE tenant_id=:t AND user_id=:u AND session_id=:s
              AND type IN ('settle_win','settle_banker','settle_void','settle_banker_void')
            """, p));
        out.put("rebate", value("""
            SELECT COALESCE(SUM(amount),0) FROM sangong_rebate_ledger
            WHERE tenant_id=:t AND user_id=:u AND session_id=:s
            """, p));
        return out;
    }

    /**
     * 一个成员在业务批次内的返水状态。
     * 待返水不只取本人流水：还包括其全部下级按级差产生的应得返水；
     * 已实际入账则同时扣除本人返水账本和代理级差账本。
     */
    public Map<String, Object> memberRebateSummary(long userId, long sessionId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", TenantContext.require())
            .addValue("u", userId).addValue("s", sessionId);
        Map<String, Object> user = jdbc.queryForMap("""
            SELECT im_user_id AS imUserId, COALESCE(player_rebate_pct,0) AS rebatePct
            FROM sangong_users WHERE tenant_id=:t AND id=:u
            """, p);
        double rebatePct = ((Number) user.get("rebatePct")).doubleValue();
        long ownExpected = value("""
            SELECT COALESCE(FLOOR(COALESCE(SUM(total_turnover),0) * :pct / 100),0)
            FROM sangong_rebate_turnover WHERE tenant_id=:t AND user_id=:u AND session_id=:s
            """, p.addValue("pct", rebatePct));
        long teamDiffExpected = value("""
            SELECT COALESCE(SUM(FLOOR(rt.total_turnover * GREATEST(0, :pct - COALESCE(child.player_rebate_pct,0)) / 100)),0)
            FROM sangong_user_hierarchy h
            JOIN sangong_users child ON child.tenant_id=h.tenant_id AND child.id=h.user_id
            JOIN sangong_rebate_turnover rt ON rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id AND rt.session_id=:s
            WHERE h.tenant_id=:t AND h.is_active=1 AND h.user_id<>:u
              AND h.path LIKE CONCAT('%/', :u, '/%')
            """, p);
        long playerCredited = value("""
            SELECT COALESCE(SUM(amount),0) FROM sangong_rebate_ledger
            WHERE tenant_id=:t AND user_id=:u AND session_id=:s AND account_type='PLAYER_REBATE'
            """, p);
        long agentDiffCredited = value("""
            SELECT COALESCE(SUM(amount),0) FROM sangong_agent_ledger
            WHERE tenant_id=:t AND session_id=:s AND agent_im_user_id=:im AND type='rebate_diff'
            """, p.addValue("im", String.valueOf(user.get("imUserId"))));
        long batchRebate = playerCredited + agentDiffCredited;
        long pendingRebate = Math.max(0L, ownExpected + teamDiffExpected - batchRebate);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchRebate", batchRebate); out.put("pendingRebate", pendingRebate);
        return out;
    }

    /** 下级团队按业务日期汇总；余额取该日期结束前每名成员最后一笔账变后的余额。 */
    public List<Map<String, Object>> teamDaily(long rootUserId, java.time.LocalDate from, java.time.LocalDate to) {
        String tenantId = TenantContext.require();
        List<java.time.LocalDate> dates = jdbc.queryForList("""
            SELECT DISTINCT business_date FROM sangong_sessions
            WHERE tenant_id=:t AND business_date BETWEEN :from AND :to
            ORDER BY business_date DESC
            """, new MapSqlParameterSource().addValue("t", tenantId).addValue("from", from).addValue("to", to),
            java.time.LocalDate.class);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (java.time.LocalDate date : dates) {
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenantId)
                .addValue("root", rootUserId).addValue("d", date);
            String scope = "h.path LIKE CONCAT('%/',:root,'/%') AND h.user_id<>:root";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("businessDate", date);
            row.put("memberCount", value("SELECT COUNT(*) FROM sangong_user_hierarchy h WHERE h.tenant_id=:t AND " + scope, p));
            row.put("totalBalance", value("""
                SELECT COALESCE(SUM(COALESCE((SELECT l.balance_after FROM sangong_ledger l
                  WHERE l.tenant_id=h.tenant_id AND l.user_id=h.user_id
                    AND l.created_at < DATE_ADD(:d, INTERVAL 1 DAY)
                  ORDER BY l.id DESC LIMIT 1),0)),0)
                FROM sangong_user_hierarchy h WHERE h.tenant_id=:t AND """ + scope, p));
            row.put("totalTurnover", value("""
                SELECT COALESCE(SUM(rt.total_turnover),0) FROM sangong_user_hierarchy h
                JOIN sangong_rebate_turnover rt ON rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id
                JOIN sangong_sessions s ON s.tenant_id=rt.tenant_id AND s.id=rt.session_id
                WHERE h.tenant_id=:t AND s.business_date=:d AND """ + scope, p));
            row.put("totalUp", value("""
                SELECT COALESCE(SUM(ABS(l.amount)),0) FROM sangong_user_hierarchy h
                JOIN sangong_ledger l ON l.tenant_id=h.tenant_id AND l.user_id=h.user_id
                JOIN sangong_sessions s ON s.tenant_id=l.tenant_id AND s.id=l.session_id
                WHERE h.tenant_id=:t AND s.business_date=:d
                  AND l.type IN ('admin_credit','credit','user_transfer_in') AND """ + scope, p));
            row.put("totalDown", value("""
                SELECT COALESCE(SUM(ABS(l.amount)),0) FROM sangong_user_hierarchy h
                JOIN sangong_ledger l ON l.tenant_id=h.tenant_id AND l.user_id=h.user_id
                JOIN sangong_sessions s ON s.tenant_id=l.tenant_id AND s.id=l.session_id
                WHERE h.tenant_id=:t AND s.business_date=:d
                  AND l.type IN ('admin_debit','debit','user_transfer_out') AND """ + scope, p));
            row.put("profitLoss", value("""
                SELECT COALESCE(SUM(l.amount),0) FROM sangong_user_hierarchy h
                JOIN sangong_ledger l ON l.tenant_id=h.tenant_id AND l.user_id=h.user_id
                JOIN sangong_sessions s ON s.tenant_id=l.tenant_id AND s.id=l.session_id
                WHERE h.tenant_id=:t AND s.business_date=:d
                  AND l.type IN ('settle_win','settle_banker','settle_void','settle_banker_void') AND """ + scope, p));
            row.put("rebate", value("""
                SELECT COALESCE(SUM(rl.amount),0) FROM sangong_user_hierarchy h
                JOIN sangong_rebate_ledger rl ON rl.tenant_id=h.tenant_id AND rl.user_id=h.user_id
                JOIN sangong_sessions s ON s.tenant_id=rl.tenant_id AND s.id=rl.session_id
                WHERE h.tenant_id=:t AND s.business_date=:d AND """ + scope, p));
            out.add(row);
        }
        return out;
    }

    private long value(String sql, MapSqlParameterSource params) {
        Long result = jdbc.queryForObject(sql, params, Long.class);
        return result == null ? 0L : result;
    }

    private static long sum(List<Map<String, Object>> rows, String key) {
        return rows.stream().mapToLong(row -> {
            Object value = row.get(key);
            return value instanceof Number number ? number.longValue() : 0L;
        }).sum();
    }

    private long turnover(String scope, MapSqlParameterSource p, String col) {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(rt." + col + "),0) FROM sangong_user_hierarchy h " +
            "LEFT JOIN sangong_rebate_turnover rt ON rt.tenant_id=h.tenant_id AND rt.user_id=h.user_id " +
            "WHERE h.tenant_id=:t AND " + scope, p, Long.class);
        return v == null ? 0L : v;
    }

    private long ledger(String scope, MapSqlParameterSource p, String ignored) {
        // 返水金额独立汇总；游戏盈亏由结算账本按用户范围汇总。
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(rl.amount),0) FROM sangong_rebate_ledger rl " +
            "JOIN sangong_user_hierarchy h ON h.tenant_id=rl.tenant_id AND h.user_id=rl.user_id " +
            "WHERE h.tenant_id=:t AND " + scope, p, Long.class);
        return v == null ? 0L : v;
    }

    private long transferSum(String scope, MapSqlParameterSource p, boolean incoming) {
        String types = incoming ? "('admin_credit','credit','user_transfer_in')" : "('admin_debit','debit','user_transfer_out')";
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(ABS(l.amount)),0) FROM sangong_ledger l " +
            "JOIN sangong_user_hierarchy h ON h.tenant_id=l.tenant_id AND h.user_id=l.user_id " +
            "WHERE h.tenant_id=:t AND " + scope + " AND l.type IN " + types, p, Long.class);
        return v == null ? 0L : v;
    }

    private long settlementNet(String scope, MapSqlParameterSource p) {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN l.type IN ('settle_win','settle_banker') " +
            "THEN l.amount WHEN l.type IN ('settle_void','settle_banker_void') THEN l.amount ELSE 0 END),0) " +
            "FROM sangong_ledger l JOIN sangong_user_hierarchy h ON h.tenant_id=l.tenant_id AND h.user_id=l.user_id " +
            "WHERE h.tenant_id=:t AND " + scope, p, Long.class);
        return v == null ? 0L : v;
    }
}
