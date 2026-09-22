package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongRebatePending;
import com.chat99.sangong.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RebatePendingRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<SangongRebatePending> M = (rs, i) -> {
        SangongRebatePending p = new SangongRebatePending();
        p.setId(rs.getLong("id"));
        p.setTenantId(rs.getString("tenant_id"));
        p.setRoundId(rs.getLong("round_id"));
        p.setUserId(rs.getLong("user_id"));
        p.setSide(rs.getString("side"));
        p.setPlayerAmount(rs.getLong("player_amount"));
        p.setPlayerRebate(rs.getLong("player_rebate"));
        p.setAgentRebate(rs.getLong("agent_rebate"));
        try { p.setProcessedAt(ts(rs.getTimestamp("processed_at"))); } catch (Exception ignored) {}
        try { p.setCreatedAt(ts(rs.getTimestamp("created_at"))); } catch (Exception ignored) {}
        return p;
    };

    public RebatePendingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * INSERT IGNORE：若 (round_id, user_id, side) 已存在则跳过。
     * 返回受影响的行数（0 表示已存在，1 表示新插入）。
     */
    public int insertIgnore(String tenantId, long roundId, long userId, String side, long playerAmount) {
        return jdbc.update(
            "INSERT IGNORE INTO sangong_rebate_pending(tenant_id, round_id, user_id, side, player_amount) " +
                "VALUES(:t,:r,:u,:s,:a)",
            new MapSqlParameterSource()
                .addValue("t", tenantId)
                .addValue("r", roundId)
                .addValue("u", userId)
                .addValue("s", side)
                .addValue("a", playerAmount));
    }

    public List<SangongRebatePending> findUnprocessedByRound(long roundId) {
        return jdbc.query(
            "SELECT * FROM sangong_rebate_pending " +
                "WHERE tenant_id=:t AND round_id=:r AND processed_at IS NULL " +
                "ORDER BY side, user_id",
            new MapSqlParameterSource()
                .addValue("t", TenantContext.require())
                .addValue("r", roundId),
            M);
    }

    public List<SangongRebatePending> findAllByRound(long roundId) {
        return jdbc.query(
            "SELECT * FROM sangong_rebate_pending WHERE tenant_id=:t AND round_id=:r ORDER BY id",
            new MapSqlParameterSource()
                .addValue("t", TenantContext.require())
                .addValue("r", roundId),
            M);
    }

    public void markProcessed(long id, long playerRebate, long agentRebate) {
        // 多租户防御：id 已是 PK 全局唯一，但加 tenant_id 过滤避免传错 id 时跨租户覆盖。
        // 实际业务流程里 id 由 findUnprocessedByRound/findAllByRound 拿到的行，调用方线程都带 ctx，
        // 这里 require() 不会误抛。
        jdbc.update(
            "UPDATE sangong_rebate_pending SET processed_at=NOW(), player_rebate=:pr, agent_rebate=:ar " +
                "WHERE id=:id AND tenant_id=:t",
            new MapSqlParameterSource()
                .addValue("pr", playerRebate)
                .addValue("ar", agentRebate)
                .addValue("id", id)
                .addValue("t", com.chat99.sangong.tenant.TenantContext.require()));
    }
}