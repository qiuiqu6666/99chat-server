package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongLedger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public LedgerRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    public SangongLedger insert(SangongLedger ledger) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sangong_ledger(tenant_id,user_id,group_id,session_id,type,amount,balance_after,ref_type,ref_id,note,operator)
            VALUES(:tenantId,:userId,:groupId,:sessionId,:type,:amount,:balanceAfter,:refType,:refId,:note,:operator)
            """,
            new MapSqlParameterSource()
                .addValue("tenantId", com.chat99.sangong.tenant.TenantContext.require())
                .addValue("userId", ledger.getUserId())
                .addValue("groupId", ledger.getGroupId())
                .addValue("sessionId", ledger.getSessionId())
                .addValue("type", ledger.getType())
                .addValue("amount", ledger.getAmount())
                .addValue("balanceAfter", ledger.getBalanceAfter())
                .addValue("refType", ledger.getRefType())
                .addValue("refId", ledger.getRefId())
                .addValue("note", ledger.getNote() == null ? "" : ledger.getNote())
                .addValue("operator", ledger.getOperator() == null ? "" : ledger.getOperator()),
            kh, new String[]{"id"});
        ledger.setId(kh.getKey().longValue());
        return ledger;
    }

    /** 流水查询（用户/会话过滤，按 id 倒序，最多 500 条）。 */
    public java.util.List<java.util.Map<String, Object>> listFlow(Long userId, Long sessionId) {
        StringBuilder sql = new StringBuilder("""
            SELECT l.id AS ledgerId, l.user_id AS userId, u.im_user_id AS imUserId, u.nickname AS nickname,
                   l.session_id AS sessionId, l.type AS type, l.amount AS amount, l.balance_after AS balanceAfter,
                   l.ref_type AS refType, l.ref_id AS refId, l.note AS note, l.operator AS operator,
                   l.created_at AS createdAt
            FROM sangong_ledger l LEFT JOIN sangong_users u ON u.id = l.user_id
            WHERE l.tenant_id=:tenantId
            """);
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", com.chat99.sangong.tenant.TenantContext.require());
        if (userId != null) {
            sql.append(" AND l.user_id=:userId");
            params.addValue("userId", userId);
        }
        if (sessionId != null) {
            sql.append(" AND l.session_id=:sessionId");
            params.addValue("sessionId", sessionId);
        }
        sql.append(" ORDER BY l.id DESC LIMIT 500");
        return jdbc.queryForList(sql.toString(), params).stream()
            .map(row -> (java.util.Map<String, Object>) new java.util.LinkedHashMap<String, Object>(row))
            .toList();
    }

    /**
     * 会话上下分合计（credit/debit + admin_*；session_id 匹配或 null+created_at>=startedAt）。
     * 多租户隔离：按 tenant_id 限定（session_id 是 DB 范围 auto-increment，不同群可能撞 id）。
     * @return userId -> {creditTotal, debitTotal}
     */
    public java.util.Map<Long, java.util.Map<String, Long>> sumCreditDebitForSession(long sessionId, java.time.Instant startedAt) {
        return sumCreditDebitForSession(sessionId, startedAt, null);
    }

    public java.util.Map<Long, java.util.Map<String, Long>> sumCreditDebitForSession(
            long sessionId, java.time.Instant startedAt, java.time.Instant stoppedAt) {
        String sql = """
            SELECT user_id AS userId,
                   SUM(CASE WHEN type IN ('credit','admin_credit') THEN ABS(amount) ELSE 0 END) AS creditTotal,
                   SUM(CASE WHEN type IN ('debit','admin_debit') THEN ABS(amount) ELSE 0 END) AS debitTotal
            FROM sangong_ledger
            WHERE tenant_id = :tenantId
              AND (
                (session_id=:sessionId AND type IN ('credit','debit','admin_credit','admin_debit'))
                OR (session_id IS NULL AND type IN ('admin_credit','admin_debit')
                    AND (:startedAt IS NULL OR created_at >= :startedAt)
                    AND (:stoppedAt IS NULL OR created_at <= :stoppedAt))
            )
            GROUP BY user_id
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", com.chat99.sangong.tenant.TenantContext.require())
            .addValue("sessionId", sessionId)
            .addValue("startedAt", startedAt == null ? null : java.sql.Timestamp.from(startedAt))
            .addValue("stoppedAt", stoppedAt == null ? null : java.sql.Timestamp.from(stoppedAt));
        java.util.Map<Long, java.util.Map<String, Long>> out = new java.util.LinkedHashMap<>();
        for (java.util.Map<String, Object> row : jdbc.queryForList(sql, params)) {
            long userId = ((Number) row.get("userId")).longValue();
            java.util.Map<String, Long> totals = new java.util.LinkedHashMap<>();
            totals.put("creditTotal", ((Number) row.get("creditTotal")).longValue());
            totals.put("debitTotal", ((Number) row.get("debitTotal")).longValue());
            out.put(userId, totals);
        }
        return out;
    }
}
