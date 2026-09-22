package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongBet;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class BetRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final RowMapper<SangongBet> M = (rs, i) -> {
        SangongBet b = new SangongBet();
        b.setId(rs.getLong("id"));
        b.setRoundId(rs.getLong("round_id"));
        b.setUserId(rs.getLong("user_id"));
        b.setDoor(rs.getInt("door"));
        b.setAmount(rs.getLong("amount"));
        b.setProxy(rs.getBoolean("is_proxy"));
        long im = rs.getLong("im_message_id");
        b.setImMessageId(rs.wasNull() ? null : im);
        Timestamp c = rs.getTimestamp("created_at");
        b.setCreatedAt(c == null ? null : c.toInstant());
        return b;
    };
    public BetRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<SangongBet> findById(long id) {
        var list = jdbc.query("SELECT * FROM sangong_bets WHERE id=:id", new MapSqlParameterSource("id", id), M);
        return list.stream().findFirst();
    }
    public List<SangongBet> listByRound(long roundId) {
        return jdbc.query("SELECT * FROM sangong_bets WHERE round_id=:r ORDER BY id", new MapSqlParameterSource("r", roundId), M);
    }
    public List<SangongBet> listByImMessageId(long imMessageId) {
        return jdbc.query("SELECT * FROM sangong_bets WHERE im_message_id=:m ORDER BY id", new MapSqlParameterSource("m", imMessageId), M);
    }
    public boolean existsByRoundAndUser(long roundId, long userId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_bets WHERE round_id=:r AND user_id=:u",
            new MapSqlParameterSource().addValue("r", roundId).addValue("u", userId), Integer.class);
        return c != null && c > 0;
    }
    public long insert(long roundId, long userId, int door, long amount, boolean isProxy, Long imMessageId) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO sangong_bets(round_id,user_id,door,amount,is_proxy,im_message_id) VALUES(:r,:u,:d,:a,:p,:m)",
            new MapSqlParameterSource().addValue("r", roundId).addValue("u", userId).addValue("d", door)
                .addValue("a", amount).addValue("p", isProxy).addValue("m", imMessageId), kh, new String[]{"id"});
        return kh.getKey().longValue();
    }
    public void delete(long id) {
        jdbc.update("DELETE FROM sangong_bets WHERE id=:id", new MapSqlParameterSource("id", id));
    }
    public void deleteByRound(long roundId) {
        jdbc.update("DELETE FROM sangong_bets WHERE round_id=:r", new MapSqlParameterSource("r", roundId));
    }
    public long sumDoorAmount(long roundId, int door) {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM sangong_bets WHERE round_id=:r AND door=:d",
            new MapSqlParameterSource().addValue("r", roundId).addValue("d", door), Long.class);
        return v == null ? 0 : v;
    }
    public int countByRound(long roundId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_bets WHERE round_id=:r",
            new MapSqlParameterSource("r", roundId), Integer.class);
        return c == null ? 0 : c;
    }
    /** door => total */
    public Map<Integer, Long> doorTotals(long roundId) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT door, SUM(amount) AS total FROM sangong_bets WHERE round_id=:r GROUP BY door ORDER BY door",
            new MapSqlParameterSource("r", roundId), rs -> { out.put(rs.getInt("door"), rs.getLong("total")); });
        return out;
    }
    /** rows: user_id, nickname, im_user_id, door, amount(sum)
     *
     * 多租户防御：sangong_bets 不带 tenant_id 列,scope 靠 round_id;但调用方传错 round_id
     * 会无声读到跨租户数据。加 EXISTS 子查询校验 round 属于当前 TenantContext。
     */
    public List<Map<String, Object>> userDoorRows(long roundId) {
        return jdbc.queryForList("""
            SELECT b.user_id, u.nickname, u.im_user_id, b.door, SUM(b.amount) AS amount
            FROM sangong_bets b JOIN sangong_users u ON u.id = b.user_id
            WHERE b.round_id=:r
              AND EXISTS (SELECT 1 FROM sangong_rounds r
                          WHERE r.id = b.round_id AND r.tenant_id = :t)
            GROUP BY b.user_id, u.nickname, u.im_user_id, b.door
            ORDER BY b.user_id, b.door
            """, new MapSqlParameterSource()
                .addValue("r", roundId)
                .addValue("t", com.chat99.sangong.tenant.TenantContext.require()));
    }
    /** admin(direct) bets rows: user_id, nickname, im_user_id, door, amount (per bet) */
    public List<Map<String, Object>> adminBetRows(long roundId) {
        return jdbc.queryForList("""
            SELECT b.user_id, u.nickname, u.im_user_id, b.door, b.amount
            FROM sangong_bets b JOIN sangong_users u ON u.id = b.user_id
            WHERE b.round_id=:r AND b.im_message_id IS NULL
              AND EXISTS (SELECT 1 FROM sangong_rounds r
                          WHERE r.id = b.round_id AND r.tenant_id = :t)
            ORDER BY b.user_id, b.door
            """, new MapSqlParameterSource()
                .addValue("r", roundId)
                .addValue("t", com.chat99.sangong.tenant.TenantContext.require()));
    }
}
