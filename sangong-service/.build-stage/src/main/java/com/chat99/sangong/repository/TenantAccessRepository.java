package com.chat99.sangong.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账号（主服务用户）与租户（游戏群）的访问关系。 */
@Repository
public class TenantAccessRepository {
    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_ADMIN = "admin";

    public record Access(String mainUserId, String tenantId, String role, boolean isDefault) {}

    private final NamedParameterJdbcTemplate jdbc;

    public TenantAccessRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Access map(Map<String, Object> row) {
        Object d = row.get("is_default");
        boolean isDefault = d instanceof Boolean b ? b : d instanceof Number n && n.intValue() != 0;
        return new Access(
            String.valueOf(row.get("main_user_id")),
            String.valueOf(row.get("tenant_id")),
            String.valueOf(row.get("role")),
            isDefault);
    }

    public Optional<Access> find(String mainUserId, String tenantId) {
        var rows = jdbc.queryForList(
            "SELECT * FROM sangong_tenant_access WHERE main_user_id=:u AND tenant_id=:t",
            new MapSqlParameterSource().addValue("u", mainUserId).addValue("t", tenantId));
        return rows.stream().findFirst().map(TenantAccessRepository::map);
    }

    public List<Access> listByUser(String mainUserId) {
        return jdbc.queryForList(
                "SELECT * FROM sangong_tenant_access WHERE main_user_id=:u ORDER BY created_at ASC",
                new MapSqlParameterSource("u", mainUserId))
            .stream().map(TenantAccessRepository::map).toList();
    }

    public List<Access> listByTenant(String tenantId) {
        return jdbc.queryForList(
                "SELECT * FROM sangong_tenant_access WHERE tenant_id=:t ORDER BY created_at ASC",
                new MapSqlParameterSource("t", tenantId))
            .stream().map(TenantAccessRepository::map).toList();
    }

    public int countByTenant(String tenantId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sangong_tenant_access WHERE tenant_id=:t",
            new MapSqlParameterSource("t", tenantId), Integer.class);
        return n == null ? 0 : n;
    }

    public int countOwners(String tenantId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM sangong_tenant_access WHERE tenant_id=:t AND role=:r",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("r", ROLE_OWNER),
            Integer.class);
        return n == null ? 0 : n;
    }

    public void upsert(String mainUserId, String tenantId, String role) {
        jdbc.update("""
            INSERT INTO sangong_tenant_access(main_user_id, tenant_id, role, created_at, updated_at)
            VALUES(:u,:t,:r,NOW(),NOW())
            ON DUPLICATE KEY UPDATE role=VALUES(role), updated_at=NOW()
            """,
            new MapSqlParameterSource()
                .addValue("u", mainUserId).addValue("t", tenantId).addValue("r", role));
    }

    public boolean delete(String mainUserId, String tenantId) {
        return jdbc.update(
            "DELETE FROM sangong_tenant_access WHERE main_user_id=:u AND tenant_id=:t",
            new MapSqlParameterSource().addValue("u", mainUserId).addValue("t", tenantId)) > 0;
    }

    public void setDefault(String mainUserId, String tenantId) {
        var params = new MapSqlParameterSource().addValue("u", mainUserId).addValue("t", tenantId);
        jdbc.update("UPDATE sangong_tenant_access SET is_default=0 WHERE main_user_id=:u", params);
        jdbc.update(
            "UPDATE sangong_tenant_access SET is_default=1, updated_at=NOW() WHERE main_user_id=:u AND tenant_id=:t",
            params);
    }
}
