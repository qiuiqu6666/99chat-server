package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongTenantAgentGroup;
import com.chat99.sangong.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TenantAgentGroupRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<SangongTenantAgentGroup> M = (rs, i) -> {
        SangongTenantAgentGroup g = new SangongTenantAgentGroup();
        g.setId(rs.getLong("id"));
        g.setTenantId(rs.getString("tenant_id"));
        g.setGroupId(rs.getLong("group_id"));
        g.setAgentImUserId(rs.getString("agent_im_user_id"));
        g.setMaxRebatePct(rs.getDouble("max_rebate_pct"));
        g.setActive(rs.getBoolean("is_active"));
        try { g.setNote(rs.getString("note")); } catch (Exception ignored) {}
        try { g.setCreatedAt(ts(rs.getTimestamp("created_at"))); } catch (Exception ignored) {}
        try { g.setUpdatedAt(ts(rs.getTimestamp("updated_at"))); } catch (Exception ignored) {}
        return g;
    };

    public TenantAgentGroupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
当前租户下按 (tenant_id, group_id) 查一条代理实例。
 */
    public Optional<SangongTenantAgentGroup> findByTenantAndGroup(String tenantId, long groupId) {
        var list = jdbc.query(
            "SELECT * FROM sangong_tenant_agent_groups WHERE tenant_id=:t AND group_id=:g",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("g", groupId), M);
        return list.stream().findFirst();
    }

    /**
当前租户下 (tenant_id, group_id) 加锁行。
     */
    public SangongTenantAgentGroup lockByTenantAndGroup(String tenantId, long groupId) {
        var list = jdbc.query(
            "SELECT * FROM sangong_tenant_agent_groups WHERE tenant_id=:t AND group_id=:g FOR UPDATE",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("g", groupId), M);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
当前租户下,找当前 IM 用户作为代理的所有分组。
 */
    public List<SangongTenantAgentGroup> findActiveByAgentInTenant(String tenantId, String agentImUserId) {
        return jdbc.query(
            "SELECT * FROM sangong_tenant_agent_groups " +
                "WHERE tenant_id=:t AND agent_im_user_id=:a AND is_active=1 ORDER BY id",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("a", agentImUserId), M);
    }

    /**
找当前 IM 用户在当前租户的"主"代理分组(取第一条 active)。兼容老接口。
     */
    public Optional<SangongTenantAgentGroup> findActiveByAgentFirst(String tenantId, String agentImUserId) {
        var list = findActiveByAgentInTenant(tenantId, agentImUserId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
当前租户下,某 (tenant_id, group_id) 不存在时创建一条占位。
 */
    public SangongTenantAgentGroup ensureExists(String tenantId, long groupId) {
        SangongTenantAgentGroup existing = lockByTenantAndGroup(tenantId, groupId);
        if (existing != null) return existing;
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(
            "INSERT INTO sangong_tenant_agent_groups(tenant_id, group_id, agent_im_user_id, max_rebate_pct, is_active) " +
                "VALUES(:t, :g, '', 0, 1)",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("g", groupId),
            kh, new String[]{"id"});
        return lockByTenantAndGroup(tenantId, groupId);
    }

    /**
群主更新代理配置(agent_im_user_id / max_rebate_pct / is_active / note)。
     */
    public SangongTenantAgentGroup updateAgentConfig(String tenantId, long groupId,
                                                     String agentImUserId, Double maxRebatePct,
                                                     Boolean isActive, String note) {
        jdbc.update("""
            UPDATE sangong_tenant_agent_groups SET
              agent_im_user_id = COALESCE(:a, agent_im_user_id),
              max_rebate_pct   = COALESCE(:mr, max_rebate_pct),
              is_active        = COALESCE(:act, is_active),
              note             = COALESCE(:n, note),
              updated_at       = NOW()
            WHERE tenant_id = :t AND group_id = :g
            """,
            new MapSqlParameterSource()
                .addValue("a", agentImUserId)
                .addValue("mr", maxRebatePct)
                .addValue("act", isActive)
                .addValue("n", note)
                .addValue("t", tenantId)
                .addValue("g", groupId));
        return lockByTenantAndGroup(tenantId, groupId);
    }
}