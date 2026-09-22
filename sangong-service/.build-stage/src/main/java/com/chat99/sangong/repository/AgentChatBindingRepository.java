package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongAgentChatBinding;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentChatBindingRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static Instant ts(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static final RowMapper<SangongAgentChatBinding> MAPPER = (rs, i) -> {
        SangongAgentChatBinding row = new SangongAgentChatBinding();
        row.setId(rs.getLong("id"));
        row.setTenantId(rs.getString("tenant_id"));
        row.setAgentImUserId(rs.getString("agent_im_user_id"));
        row.setAgentImGroupId(rs.getString("agent_im_group_id"));
        row.setActive(rs.getBoolean("is_active"));
        row.setCreatedAt(ts(rs.getTimestamp("created_at")));
        row.setUpdatedAt(ts(rs.getTimestamp("updated_at")));
        return row;
    };

    public AgentChatBindingRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<SangongAgentChatBinding> findActive(String agentImUserId, String agentImGroupId) {
        List<SangongAgentChatBinding> rows = jdbc.query("""
            SELECT * FROM sangong_agent_chat_bindings
            WHERE agent_im_user_id=:u AND agent_im_group_id=:g AND is_active=1
            """, new MapSqlParameterSource().addValue("u", agentImUserId).addValue("g", agentImGroupId), MAPPER);
        return rows.stream().findFirst();
    }

    public Optional<SangongAgentChatBinding> findByTenantAndUser(String tenantId, String agentImUserId) {
        List<SangongAgentChatBinding> rows = jdbc.query("""
            SELECT * FROM sangong_agent_chat_bindings WHERE tenant_id=:t AND agent_im_user_id=:u
            """, new MapSqlParameterSource().addValue("t", tenantId).addValue("u", agentImUserId), MAPPER);
        return rows.stream().findFirst();
    }

    public SangongAgentChatBinding upsert(String tenantId, String agentImUserId, String agentImGroupId, boolean active) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("t", tenantId)
            .addValue("u", agentImUserId).addValue("g", agentImGroupId).addValue("a", active);
        int updated = jdbc.update("""
            UPDATE sangong_agent_chat_bindings SET agent_im_group_id=:g, is_active=:a, updated_at=NOW()
            WHERE tenant_id=:t AND agent_im_user_id=:u
            """, params);
        if (updated == 0) {
            jdbc.update("""
                INSERT INTO sangong_agent_chat_bindings
                  (tenant_id, agent_im_user_id, agent_im_group_id, is_active)
                VALUES (:t, :u, :g, :a)
                """, params);
        }
        return findByTenantAndUser(tenantId, agentImUserId).orElseThrow();
    }

    public void delete(String tenantId, String agentImUserId) {
        jdbc.update("DELETE FROM sangong_agent_chat_bindings WHERE tenant_id=:t AND agent_im_user_id=:u",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("u", agentImUserId));
    }

    /** 代理资格只看用户在该租户的返水比例是否大于 0，不依赖旧代理分组关系。 */
    public Optional<Map<String, Object>> findRebateAgentSummary(String tenantId, String agentImUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT u.id AS userId, u.im_user_id AS imUserId, u.nickname,
                   u.balance, u.player_rebate_pct AS rebatePct,
                   (SELECT COUNT(*) FROM sangong_user_hierarchy h
                    WHERE h.tenant_id=u.tenant_id AND h.parent_user_id=u.id AND h.is_active=1) AS directPlayerCount,
                   (SELECT COUNT(*) FROM sangong_user_hierarchy h
                    WHERE h.tenant_id=u.tenant_id AND h.user_id<>u.id AND h.is_active=1
                      AND h.path LIKE CONCAT('%/', u.id, '/%')) AS playerCount,
                   COALESCE((SELECT GREATEST(0, a.amount_total-a.amount_claimed)
                    FROM sangong_rebate_accounts a WHERE a.tenant_id=u.tenant_id
                      AND a.user_id=u.id AND a.account_type='AGENT_DIFF'), 0) AS pendingAgentRebate
            FROM sangong_users u
            WHERE u.tenant_id=:t AND u.im_user_id=:u AND u.player_rebate_pct>0
            """, new MapSqlParameterSource().addValue("t", tenantId).addValue("u", agentImUserId));
        return rows.stream().findFirst();
    }
}
