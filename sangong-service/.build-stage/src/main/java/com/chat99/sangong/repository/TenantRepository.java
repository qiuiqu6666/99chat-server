package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongTenant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<SangongTenant> M = (rs, i) -> {
        SangongTenant t = new SangongTenant();
        t.setTenantId(rs.getString("tenant_id"));
        t.setName(rs.getString("name"));
        t.setImGroupGameId(rs.getString("im_group_game_id"));
        t.setImGroupAdminStatsId(rs.getString("im_group_admin_stats_id"));
        t.setImGroupLedgerId(rs.getString("im_group_ledger_id"));
        try { t.setImGroupWaterId(rs.getString("im_group_water_id")); } catch (Exception ignored) { t.setImGroupWaterId(""); }
        t.setImBotUserId(rs.getString("im_bot_user_id"));
        t.setActive(rs.getBoolean("active"));
        try { t.setCreatedAt(ts(rs.getTimestamp("created_at"))); } catch (Exception ignored) {}
        try { t.setUpdatedAt(ts(rs.getTimestamp("updated_at"))); } catch (Exception ignored) {}
        return t;
    };

    public TenantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SangongTenant> listAll() {
        return jdbc.query("SELECT * FROM sangong_tenants ORDER BY created_at ASC, tenant_id ASC", M);
    }

    public List<SangongTenant> listActive() {
        return jdbc.query("SELECT * FROM sangong_tenants WHERE active=1 ORDER BY created_at ASC", M);
    }

    public Optional<SangongTenant> findById(String tenantId) {
        var list = jdbc.query("SELECT * FROM sangong_tenants WHERE tenant_id=:id",
            new MapSqlParameterSource("id", tenantId), M);
        return list.stream().findFirst();
    }

    public Optional<SangongTenant> findByGameGroupId(String groupId) {
        var list = jdbc.query(
            "SELECT * FROM sangong_tenants WHERE im_group_game_id=:g AND active=1 LIMIT 1",
            new MapSqlParameterSource("g", groupId), M);
        return list.stream().findFirst();
    }

    public void insert(SangongTenant t) {
        jdbc.update("""
            INSERT INTO sangong_tenants(
              tenant_id, name, im_group_game_id, im_group_admin_stats_id,
              im_group_ledger_id, im_group_water_id, im_bot_user_id, active, created_at, updated_at)
            VALUES(:id,:name,:game,:admin,:ledger,:water,:bot,:active,NOW(),NOW())
            """, params(t));
    }

    public void update(SangongTenant t) {
        jdbc.update("""
            UPDATE sangong_tenants SET name=:name, im_group_game_id=:game,
              im_group_admin_stats_id=:admin, im_group_ledger_id=:ledger,
              im_group_water_id=:water, im_bot_user_id=:bot, active=:active, updated_at=NOW()
            WHERE tenant_id=:id
            """, params(t));
    }

    private static MapSqlParameterSource params(SangongTenant t) {
        return new MapSqlParameterSource()
            .addValue("id", t.getTenantId())
            .addValue("name", t.getName() == null ? "" : t.getName())
            .addValue("game", t.getImGroupGameId())
            .addValue("admin", nz(t.getImGroupAdminStatsId()))
            .addValue("ledger", nz(t.getImGroupLedgerId()))
            .addValue("water", nz(t.getImGroupWaterId()))
            .addValue("bot", nz(t.getImBotUserId()))
            .addValue("active", t.isActive() ? 1 : 0);
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
