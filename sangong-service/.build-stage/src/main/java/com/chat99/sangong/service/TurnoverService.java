package com.chat99.sangong.service;

import com.chat99.sangong.tenant.TenantContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 按租户和用户累计可返水流水。所有写入均使用唯一键幂等。 */
@Service
public class TurnoverService {
    private final NamedParameterJdbcTemplate jdbc;
    public TurnoverService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void addPlayerTurnover(long userId, long amount, long sessionId) {
        add(userId, 0L, amount, 1, sessionId);
    }

    public void removePlayerTurnover(long userId, long amount, long sessionId) {
        add(userId, 0L, -amount, 0, sessionId);
    }

    public void addBankerTurnover(long userId, long amount, long roundId, long sessionId) {
        if (amount <= 0) return;
        String tenant = TenantContext.require();
        int inserted = jdbc.update("INSERT IGNORE INTO sangong_rebate_turnover_events " +
                "(tenant_id,session_id,round_id,user_id,turnover_type,stat_date,amount) " +
                "SELECT :t,:s,:r,:u,'BANKER',COALESCE(business_date,DATE(started_at)),:amount FROM sangong_sessions WHERE id=:s AND tenant_id=:t",
            new MapSqlParameterSource().addValue("t", tenant).addValue("r", roundId)
                .addValue("s", sessionId).addValue("u", userId).addValue("amount", amount));
        if (inserted == 0) return;
        jdbc.update("INSERT INTO sangong_rebate_turnover " +
                "(tenant_id,session_id,stat_date,user_id,banker_turnover,total_turnover,source_round_count) " +
                "SELECT :t,:s,COALESCE(business_date,DATE(started_at)),:u,:b,:b,1 FROM sangong_sessions WHERE id=:s AND tenant_id=:t " +
                "ON DUPLICATE KEY UPDATE " +
                "banker_turnover=banker_turnover+:b,total_turnover=total_turnover+:b,source_round_count=source_round_count+1",
            new MapSqlParameterSource().addValue("t", tenant).addValue("s", sessionId)
                .addValue("u", userId).addValue("b", amount));
        syncAccount(userId);
    }

    public void reverseRound(long roundId) {
        String tenant = TenantContext.require();
        var events = jdbc.queryForList("SELECT user_id,turnover_type,stat_date,amount FROM sangong_rebate_turnover_events " +
            "WHERE tenant_id=:t AND round_id=:r AND reversed_at IS NULL FOR UPDATE",
            new MapSqlParameterSource().addValue("t", tenant).addValue("r", roundId));
        for (var e : events) {
            long userId = ((Number) e.get("user_id")).longValue();
            long amount = ((Number) e.get("amount")).longValue();
            jdbc.update("UPDATE sangong_rebate_turnover SET banker_turnover=GREATEST(0,banker_turnover-:a), " +
                "total_turnover=GREATEST(0,total_turnover-:a) WHERE tenant_id=:t AND user_id=:u AND stat_date=:d",
                new MapSqlParameterSource().addValue("t", tenant).addValue("u", userId).addValue("d", e.get("stat_date")).addValue("a", amount));
            jdbc.update("UPDATE sangong_rebate_turnover_events SET reversed_at=NOW() " +
                "WHERE tenant_id=:t AND round_id=:r AND user_id=:u AND turnover_type=:type AND reversed_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenant).addValue("r", roundId).addValue("u", userId).addValue("type", e.get("turnover_type")));
            syncAccount(userId);
        }
    }

    private void add(long userId, long banker, long player, int rounds, long sessionId) {
        if (player == 0 && banker == 0) return;
        String tenant = TenantContext.require();
        jdbc.update("INSERT INTO sangong_rebate_turnover " +
                "(tenant_id,session_id,stat_date,user_id,banker_turnover,player_turnover,total_turnover,source_round_count) " +
                "SELECT :t,:s,COALESCE(business_date,DATE(started_at)),:u,:b,:p,:total,:rounds FROM sangong_sessions WHERE id=:s AND tenant_id=:t " +
                "ON DUPLICATE KEY UPDATE " +
                "banker_turnover=GREATEST(0,banker_turnover+:b)," +
                "player_turnover=GREATEST(0,player_turnover+:p)," +
                "total_turnover=GREATEST(0,total_turnover+:total)",
            new MapSqlParameterSource().addValue("t", tenant).addValue("s", sessionId)
                .addValue("u", userId).addValue("b", banker).addValue("p", player)
                .addValue("total", banker + player).addValue("rounds", rounds));
        syncAccount(userId);
    }

    private void syncAccount(long userId) {
        jdbc.update("INSERT INTO sangong_rebate_accounts " +
                "(tenant_id,user_id,account_type,turnover_total,rebate_pct) " +
                "SELECT :t,:u,'PLAYER_REBATE',COALESCE(SUM(total_turnover),0), " +
                "COALESCE((SELECT player_rebate_pct FROM sangong_users WHERE id=:u),0) " +
                "FROM sangong_rebate_turnover WHERE tenant_id=:t AND user_id=:u " +
                "ON DUPLICATE KEY UPDATE turnover_total=VALUES(turnover_total),rebate_pct=VALUES(rebate_pct)",
            new MapSqlParameterSource().addValue("t", TenantContext.require()).addValue("u", userId));
    }
}
