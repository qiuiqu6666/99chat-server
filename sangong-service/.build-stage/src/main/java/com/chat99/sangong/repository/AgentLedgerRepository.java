package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongAgentLedger;
import com.chat99.sangong.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class AgentLedgerRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<SangongAgentLedger> M = (rs, i) -> {
        SangongAgentLedger l = new SangongAgentLedger();
        l.setId(rs.getLong("id"));
        l.setTenantId(rs.getString("tenant_id"));
        try { long sid=rs.getLong("session_id"); l.setSessionId(rs.wasNull()?null:sid); } catch (Exception ignored) {}
        l.setGroupId(rs.getLong("group_id"));
        l.setAgentImUserId(rs.getString("agent_im_user_id"));
        l.setType(rs.getString("type"));
        l.setAmount(rs.getLong("amount"));
        l.setBalanceAfter(rs.getLong("balance_after"));
        l.setRefType(rs.getString("ref_type"));
        long rid = rs.getLong("ref_id");
        l.setRefId(rs.wasNull() ? null : rid);
        l.setNote(rs.getString("note"));
        try { l.setCreatedAt(ts(rs.getTimestamp("created_at"))); } catch (Exception ignored) {}
        return l;
    };

    public AgentLedgerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SangongAgentLedger insert(SangongAgentLedger entry) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sangong_agent_ledger(tenant_id, session_id, group_id, agent_im_user_id, type,
              amount, balance_after, ref_type, ref_id, note)
            VALUES(:t,:s,:g,:a,:type,:amount,:ba,:rt,:rid,:note)
            """,
            new MapSqlParameterSource()
                .addValue("t", entry.getTenantId() == null ? TenantContext.require() : entry.getTenantId())
                .addValue("s", entry.getSessionId())
                .addValue("g", entry.getGroupId())
                .addValue("a", entry.getAgentImUserId() == null ? "" : entry.getAgentImUserId())
                .addValue("type", entry.getType())
                .addValue("amount", entry.getAmount())
                .addValue("ba", entry.getBalanceAfter())
                .addValue("rt", entry.getRefType())
                .addValue("rid", entry.getRefId())
                .addValue("note", entry.getNote() == null ? "" : entry.getNote()),
            kh, new String[]{"id"});
        entry.setId(kh.getKey().longValue());
        return entry;
    }

    public List<SangongAgentLedger> listByGroup(long groupId, int limit) {
        return jdbc.query(
            "SELECT * FROM sangong_agent_ledger WHERE tenant_id=:t AND group_id=:g ORDER BY id DESC LIMIT :lim",
            new MapSqlParameterSource()
                .addValue("t", TenantContext.require())
                .addValue("g", groupId)
                .addValue("lim", Math.max(1, Math.min(500, limit))),
            M);
    }

    /** 反查某 round 的返水/冲正流水。 */
    public List<SangongAgentLedger> listByRound(long roundId) {
        return jdbc.query(
            "SELECT * FROM sangong_agent_ledger WHERE tenant_id=:t AND ref_type='round' AND ref_id=:r ORDER BY id",
            new MapSqlParameterSource()
                .addValue("t", TenantContext.require())
                .addValue("r", roundId),
            M);
    }
}
