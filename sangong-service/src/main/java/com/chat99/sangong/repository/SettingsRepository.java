package com.chat99.sangong.repository;

import com.chat99.sangong.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SettingsRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public SettingsRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, String> loadAll() {
        return loadAll(TenantContext.require());
    }

    public Map<String, String> loadAll(String tenantId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM sangong_settings WHERE tenant_id=:t",
            new MapSqlParameterSource("t", tenantId),
            rs -> { out.put(rs.getString("setting_key"), rs.getString("setting_value")); });
        return out;
    }

    public void upsert(String key, String value) {
        upsert(TenantContext.require(), key, value);
    }

    public void upsert(String tenantId, String key, String value) {
        int updated = jdbc.update(
            "UPDATE sangong_settings SET setting_value=:v WHERE tenant_id=:t AND setting_key=:k",
            new MapSqlParameterSource().addValue("v", value).addValue("t", tenantId).addValue("k", key));
        if (updated == 0) {
            jdbc.update(
                "INSERT INTO sangong_settings(tenant_id,setting_key,setting_value) VALUES(:t,:k,:v)",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("k", key).addValue("v", value));
        }
    }

    public void insertIfAbsent(String key, String value) {
        insertIfAbsent(TenantContext.require(), key, value);
    }

    public void insertIfAbsent(String tenantId, String key, String value) {
        jdbc.update(
            "INSERT IGNORE INTO sangong_settings(tenant_id,setting_key,setting_value) VALUES(:t,:k,:v)",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("k", key).addValue("v", value));
    }
}
