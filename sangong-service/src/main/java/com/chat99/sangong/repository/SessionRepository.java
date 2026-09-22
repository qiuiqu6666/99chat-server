package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongSession;
import com.chat99.sangong.tenant.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static Instant ts(Timestamp t) { return t == null ? null : t.toInstant(); }
    private static final RowMapper<SangongSession> M = (rs, i) -> {
        SangongSession s = new SangongSession();
        s.setId(rs.getLong("id"));
        try { s.setTenantId(rs.getString("tenant_id")); } catch (Exception ignored) {}
        s.setStatus(rs.getString("status"));
        s.setCurrentPeriodNo(rs.getInt("period_no"));
        long cr = rs.getLong("current_round_id");
        s.setCurrentRoundId(rs.wasNull() ? null : cr);
        try { s.setStartedAt(ts(rs.getTimestamp("started_at"))); } catch (Exception ignored) {}
        try { s.setStoppedAt(ts(rs.getTimestamp("stopped_at"))); } catch (Exception ignored) {}
        try { s.setBusinessDate(rs.getObject("business_date", java.time.LocalDate.class)); } catch (Exception ignored) {}
        try { s.setBatchNo(rs.getString("batch_no")); } catch (Exception ignored) {}
        return s;
    };
    public SessionRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<SangongSession> findRunning() {
        return findRunning(TenantContext.require());
    }

    public Optional<SangongSession> findRunning(String tenantId) {
        var list = jdbc.query(
            "SELECT * FROM sangong_sessions WHERE tenant_id=:t AND status='running' ORDER BY id DESC LIMIT 1",
            new MapSqlParameterSource("t", tenantId), M);
        return list.stream().findFirst();
    }

    public Optional<SangongSession> findRunningForUpdate() {
        var list = jdbc.query(
            "SELECT * FROM sangong_sessions WHERE tenant_id=:t AND status='running' ORDER BY id DESC LIMIT 1 FOR UPDATE",
            new MapSqlParameterSource("t", TenantContext.require()), M);
        return list.stream().findFirst();
    }

    public Optional<SangongSession> findById(long id) {
        var list = jdbc.query("SELECT * FROM sangong_sessions WHERE id=:id AND tenant_id=:t",
            new MapSqlParameterSource("id", id).addValue("t", TenantContext.require()), M);
        return list.stream().findFirst();
    }
    public SangongSession lockById(long id) {
        var list = jdbc.query("SELECT * FROM sangong_sessions WHERE id=:id AND tenant_id=:t FOR UPDATE",
            new MapSqlParameterSource("id", id).addValue("t", TenantContext.require()), M);
        return list.isEmpty() ? null : list.get(0);
    }
    public SangongSession insertRunning() {
        String tenantId = TenantContext.require();
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(
            """
            INSERT INTO sangong_sessions
              (tenant_id,status,period_no,started_at,business_date,batch_no,created_at,updated_at)
            SELECT :t,'running',1,NOW(),CURRENT_DATE,
              CONCAT(DATE_FORMAT(CURRENT_DATE,'%Y%m%d'), LPAD(COUNT(*)+1,2,'0')),NOW(),NOW()
            FROM sangong_sessions WHERE tenant_id=:t AND business_date=CURRENT_DATE
            """,
            new MapSqlParameterSource("t", tenantId), kh, new String[]{"id"});
        return findById(kh.getKey().longValue()).orElseThrow();
    }
    public void update(SangongSession s) {
        jdbc.update("""
            UPDATE sangong_sessions SET status=:status, period_no=:period, current_round_id=:roundId, stopped_at=:stopped, updated_at=NOW()
            WHERE id=:id AND tenant_id=:tenantId
            """, new MapSqlParameterSource()
            .addValue("status", s.getStatus())
            .addValue("period", s.getCurrentPeriodNo())
            .addValue("roundId", s.getCurrentRoundId())
            .addValue("stopped", s.getStoppedAt() == null ? null : Timestamp.from(s.getStoppedAt()))
            .addValue("id", s.getId())
            .addValue("tenantId", TenantContext.require()));
    }

    public java.util.List<SangongSession> listRecent(int limit) {
        return jdbc.query("SELECT * FROM sangong_sessions WHERE tenant_id=:t ORDER BY id DESC LIMIT :lim",
            new MapSqlParameterSource().addValue("t", TenantContext.require()).addValue("lim", Math.max(1, Math.min(limit, 100))), M);
    }

    public Optional<SangongSession> findLatest() {
        return listRecent(1).stream().findFirst();
    }

    public Optional<SangongSession> findByBatchNo(String batchNo) {
        var list = jdbc.query("SELECT * FROM sangong_sessions WHERE tenant_id=:t AND batch_no=:b",
            new MapSqlParameterSource().addValue("t", TenantContext.require()).addValue("b", batchNo), M);
        return list.stream().findFirst();
    }
}
