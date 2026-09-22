package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final RowMapper<SangongUser> MAPPER = (rs, i) -> {
        SangongUser u = new SangongUser();
        u.setId(rs.getLong("id"));
        try { u.setTenantId(rs.getString("tenant_id")); } catch (Exception ignored) {}
        u.setImUserId(rs.getString("im_user_id"));
        u.setNickname(rs.getString("nickname"));
        u.setBalance(rs.getLong("balance"));
        long g = rs.getLong("group_id");
        u.setGroupId(rs.wasNull() ? null : g);
        try {
            double p = rs.getDouble("player_rebate_pct");
            u.setPlayerRebatePct(p);
        } catch (Exception ignored) {
            u.setPlayerRebatePct(0d);
        }
        return u;
    };
    public UserRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<SangongUser> findById(long id) {
        var list = jdbc.query("SELECT * FROM sangong_users WHERE id=:id", new MapSqlParameterSource("id", id), MAPPER);
        return list.stream().findFirst();
    }
    public Optional<SangongUser> findByImUserId(String imUserId) {
        return findByTenantAndImUserId(TenantContext.require(), imUserId);
    }
    public Optional<SangongUser> findByTenantAndImUserId(String tenantId, String imUserId) {
        var list = jdbc.query(
            "SELECT * FROM sangong_users WHERE tenant_id=:t AND im_user_id=:im",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("im", imUserId), MAPPER);
        return list.stream().findFirst();
    }
    public Optional<SangongUser> findByTenantAndId(String tenantId, long id) {
        var list = jdbc.query("SELECT * FROM sangong_users WHERE tenant_id=:t AND id=:id",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("id", id), MAPPER);
        return list.stream().findFirst();
    }
    public SangongUser lockById(long id) {
        var list = jdbc.query("SELECT * FROM sangong_users WHERE id=:id FOR UPDATE", new MapSqlParameterSource("id", id), MAPPER);
        return list.isEmpty() ? null : list.get(0);
    }
    public SangongUser insert(String imUserId, String nickname) {
        String tenantId = TenantContext.require();
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(
            "INSERT INTO sangong_users(tenant_id,im_user_id,nickname,balance) VALUES(:t,:im,:nick,0)",
            new MapSqlParameterSource()
                .addValue("t", tenantId)
                .addValue("im", imUserId)
                .addValue("nick", nickname),
            kh, new String[]{"id"});
        Number key = kh.getKey();
        return findById(key.longValue()).orElseThrow();
    }
    public void updateBalance(long id, long balance) {
        jdbc.update("UPDATE sangong_users SET balance=:b WHERE id=:id",
            new MapSqlParameterSource().addValue("b", balance).addValue("id", id));
    }
    public void updateNickname(long id, String nickname) {
        jdbc.update("UPDATE sangong_users SET nickname=:n WHERE id=:id",
            new MapSqlParameterSource().addValue("n", nickname).addValue("id", id));
    }
    public void updateGroupId(long id, Long groupId) {
        jdbc.update("UPDATE sangong_users SET group_id=:g WHERE id=:id",
            new MapSqlParameterSource().addValue("g", groupId).addValue("id", id));
    }
    public void updateGroupIdAndRebatePct(long id, Long groupId, double playerRebatePct) {
        jdbc.update("UPDATE sangong_users SET group_id=:g, player_rebate_pct=:r WHERE id=:id",
            new MapSqlParameterSource()
                .addValue("g", groupId)
                .addValue("r", playerRebatePct)
                .addValue("id", id));
    }
    public void updatePlayerRebatePct(long id, double pct) {
        jdbc.update("UPDATE sangong_users SET player_rebate_pct=:r WHERE id=:id",
            new MapSqlParameterSource().addValue("r", pct).addValue("id", id));
    }
    public List<SangongUser> listAll() {
        return jdbc.query(
            "SELECT * FROM sangong_users WHERE tenant_id=:t ORDER BY id",
            new MapSqlParameterSource("t", TenantContext.require()), MAPPER);
    }

    public List<SangongUser> listByGroup(long groupId) {
        return jdbc.query(
            "SELECT * FROM sangong_users WHERE tenant_id=:t AND group_id=:g ORDER BY id",
            new MapSqlParameterSource().addValue("t", TenantContext.require()).addValue("g", groupId),
            MAPPER);
    }
}
