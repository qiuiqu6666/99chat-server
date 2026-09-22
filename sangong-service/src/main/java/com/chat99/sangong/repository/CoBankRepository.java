package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongCoBank;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CoBankRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final RowMapper<SangongCoBank> M = (rs, i) -> {
        SangongCoBank c = new SangongCoBank();
        c.setId(rs.getLong("id"));
        c.setRoundId(rs.getLong("round_id"));
        c.setUserId(rs.getLong("user_id"));
        c.setAmount(rs.getLong("amount"));
        java.sql.Timestamp t = rs.getTimestamp("created_at");
        c.setCreatedAt(t == null ? null : t.toInstant());
        return c;
    };
    public CoBankRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<SangongCoBank> listByRound(long roundId) {
        return jdbc.query("SELECT * FROM sangong_co_banks WHERE round_id=:r ORDER BY id",
            new MapSqlParameterSource("r", roundId), M);
    }
    public Optional<SangongCoBank> findByRoundAndUser(long roundId, long userId) {
        var list = jdbc.query("SELECT * FROM sangong_co_banks WHERE round_id=:r AND user_id=:u",
            new MapSqlParameterSource().addValue("r", roundId).addValue("u", userId), M);
        return list.stream().findFirst();
    }
    /**
     * 返回某 userId 在本租户内合庄过的 round_id 列表。
     *
     * 当前 sangong_co_banks 不带 tenant_id 列 — 隐式 scope 靠 round_id。
     * 为防止上游传错 userId 拿到跨租户 round_id，加 EXISTS 子查询校验每个 round
     * 属于当前 TenantContext。
     */
    public List<Long> roundIdsByUser(long userId) {
        return jdbc.queryForList("""
            SELECT c.round_id FROM sangong_co_banks c
            WHERE c.user_id = :u
              AND EXISTS (SELECT 1 FROM sangong_rounds r
                          WHERE r.id = c.round_id
                            AND r.tenant_id = :t)
            """,
            new MapSqlParameterSource()
                .addValue("u", userId)
                .addValue("t", com.chat99.sangong.tenant.TenantContext.require()),
            Long.class);
    }
    public void upsert(long roundId, long userId, long amount) {
        int updated = jdbc.update("UPDATE sangong_co_banks SET amount=:a WHERE round_id=:r AND user_id=:u",
            new MapSqlParameterSource().addValue("a", amount).addValue("r", roundId).addValue("u", userId));
        if (updated == 0) {
            jdbc.update("INSERT INTO sangong_co_banks(round_id,user_id,amount) VALUES(:r,:u,:a)",
                new MapSqlParameterSource().addValue("r", roundId).addValue("u", userId).addValue("a", amount));
        }
    }
    public int deleteByRoundAndUser(long roundId, long userId) {
        return jdbc.update("DELETE FROM sangong_co_banks WHERE round_id=:r AND user_id=:u",
            new MapSqlParameterSource().addValue("r", roundId).addValue("u", userId));
    }
    public void deleteByRound(long roundId) {
        jdbc.update("DELETE FROM sangong_co_banks WHERE round_id=:r", new MapSqlParameterSource("r", roundId));
    }
}
