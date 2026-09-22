package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongAgentBalance;
import com.chat99.sangong.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentBalanceRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<SangongAgentBalance> M = (rs, i) -> {
        SangongAgentBalance b = new SangongAgentBalance();
        b.setTenantId(rs.getString("tenant_id"));
        b.setGroupId(rs.getLong("group_id"));
        b.setAgentImUserId(rs.getString("agent_im_user_id"));
        b.setBalance(rs.getLong("balance"));
        try { b.setCreatedAt(ts(rs.getTimestamp("created_at"))); } catch (Exception ignored) {}
        try { b.setUpdatedAt(ts(rs.getTimestamp("updated_at"))); } catch (Exception ignored) {}
        return b;
    };

    public AgentBalanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 非加锁读。 */
    public Optional<SangongAgentBalance> find(String tenantId, long groupId) {
        var list = jdbc.query(
            "SELECT * FROM sangong_agent_balance WHERE tenant_id=:t AND group_id=:g",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("g", groupId), M);
        return list.stream().findFirst();
    }

    public Optional<SangongAgentBalance> findByGroup(long groupId) {
        return find(TenantContext.require(), groupId);
    }

    /** SELECT ... FOR UPDATE 锁行；不存在返回 null。 */
    public SangongAgentBalance lockByGroup(String tenantId, long groupId) {
        var list = jdbc.query(
            "SELECT * FROM sangong_agent_balance WHERE tenant_id=:t AND group_id=:g FOR UPDATE",
            new MapSqlParameterSource().addValue("t", tenantId).addValue("g", groupId), M);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 不存在则创建，初始余额 0；返回插入/已存在行。 */
    public SangongAgentBalance ensureExists(String tenantId, long groupId, String agentImUserId) {
        SangongAgentBalance existing = lockByGroup(tenantId, groupId);
        if (existing != null) {
            // 若 agent_im_user_id 与当前不同，同步（仅当原值为空时更新，避免覆盖）
            if ((existing.getAgentImUserId() == null || existing.getAgentImUserId().isEmpty())
                && agentImUserId != null && !agentImUserId.isEmpty()) {
                jdbc.update("UPDATE sangong_agent_balance SET agent_im_user_id=:a WHERE tenant_id=:t AND group_id=:g",
                    new MapSqlParameterSource().addValue("a", agentImUserId)
                        .addValue("t", tenantId).addValue("g", groupId));
                existing.setAgentImUserId(agentImUserId);
            }
            return existing;
        }
        jdbc.update(
            "INSERT INTO sangong_agent_balance(tenant_id, group_id, agent_im_user_id, balance) VALUES(:t,:g,:a,0)",
            new MapSqlParameterSource().addValue("t", tenantId)
                .addValue("g", groupId)
                .addValue("a", agentImUserId == null ? "" : agentImUserId));
        return lockByGroup(tenantId, groupId);
    }

    public void updateBalance(long groupId, long newBalance) {
        jdbc.update("UPDATE sangong_agent_balance SET balance=:b WHERE tenant_id=:t AND group_id=:g",
            new MapSqlParameterSource().addValue("b", newBalance)
                .addValue("t", TenantContext.require())
                .addValue("g", groupId));
    }
}