package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongRound;
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
public class RoundRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static Instant ts(Timestamp t){ return t==null?null:t.toInstant(); }
    private static Long nl(java.sql.ResultSet rs, String c) throws java.sql.SQLException {
        long v = rs.getLong(c); return rs.wasNull()?null:v;
    }
    private static Integer ni(java.sql.ResultSet rs, String c) throws java.sql.SQLException {
        int v = rs.getInt(c); return rs.wasNull()?null:v;
    }
    private final RowMapper<SangongRound> M = (rs, i) -> {
        SangongRound r = new SangongRound();
        r.setId(rs.getLong("id"));
        r.setSessionId(rs.getLong("session_id"));
        r.setPeriodNo(rs.getInt("period_no"));
        r.setStatus(rs.getString("status"));
        r.setBankerUserId(nl(rs,"banker_user_id"));
        r.setBankerDoor(ni(rs,"banker_door"));
        r.setBankerLimit(nl(rs,"banker_limit"));
        try { r.setCoBankClosedAt(ts(rs.getTimestamp("co_bank_closed_at"))); } catch(Exception ignored){}
        try { r.setBetWindowOpenAt(ts(rs.getTimestamp("bet_window_open_at"))); } catch(Exception ignored){}
        try { r.setBetWindowCloseAt(ts(rs.getTimestamp("bet_window_close_at"))); } catch(Exception ignored){}
        try { r.setBetWindowCloseMessageId(nl(rs,"bet_window_close_message_id")); } catch(Exception ignored){}
        try { r.setBetSummaryTextMsgSeq(nl(rs,"bet_summary_text_msg_seq")); } catch(Exception ignored){}
        try { r.setBetSummaryImageMsgSeq(nl(rs,"bet_summary_image_msg_seq")); } catch(Exception ignored){}
        try { r.setCoBankSummaryMsgSeq(nl(rs,"co_bank_summary_msg_seq")); } catch(Exception ignored){}
        try { r.setDrawLockedAt(ts(rs.getTimestamp("draw_locked_at"))); } catch(Exception ignored){}
        try { r.setSettledAt(ts(rs.getTimestamp("settled_at"))); } catch(Exception ignored){}
        return r;
    };
    public RoundRepository(NamedParameterJdbcTemplate jdbc){ this.jdbc=jdbc; }
    public Optional<SangongRound> findById(long id){
        var list=jdbc.query("SELECT * FROM sangong_rounds WHERE id=:id AND tenant_id=:t",
            new MapSqlParameterSource("id",id).addValue("t", com.chat99.sangong.tenant.TenantContext.require()), M);
        return list.stream().findFirst();
    }
    public SangongRound lockById(long id){
        var list=jdbc.query("SELECT * FROM sangong_rounds WHERE id=:id AND tenant_id=:t FOR UPDATE",
            new MapSqlParameterSource("id",id).addValue("t", com.chat99.sangong.tenant.TenantContext.require()), M);
        return list.isEmpty()?null:list.get(0);
    }
    public Optional<SangongRound> findBySessionAndPeriod(long sessionId, int period){
        var list=jdbc.query("SELECT * FROM sangong_rounds WHERE session_id=:s AND period_no=:p",
            new MapSqlParameterSource().addValue("s",sessionId).addValue("p",period), M);
        return list.stream().findFirst();
    }
    public boolean existsLaterRound(long sessionId, int periodNo){
        Integer c=jdbc.queryForObject("SELECT COUNT(*) FROM sangong_rounds WHERE session_id=:s AND period_no>:p",
            new MapSqlParameterSource().addValue("s",sessionId).addValue("p",periodNo), Integer.class);
        return c!=null && c>0;
    }
    public SangongRound insert(long sessionId, int periodNo, String status){
        GeneratedKeyHolder kh=new GeneratedKeyHolder();
        jdbc.update(
            "INSERT INTO sangong_rounds(tenant_id,session_id,period_no,status,created_at,updated_at) VALUES(:t,:s,:p,:st,NOW(),NOW())",
            new MapSqlParameterSource()
                .addValue("t", com.chat99.sangong.tenant.TenantContext.require())
                .addValue("s",sessionId).addValue("p",periodNo).addValue("st",status),
            kh, new String[]{"id"});
        return findById(kh.getKey().longValue()).orElseThrow();
    }
    public void save(SangongRound r){
        jdbc.update("""
            UPDATE sangong_rounds SET status=:status, banker_user_id=:bankerUserId, banker_door=:bankerDoor,
            banker_limit=:bankerLimit, co_bank_closed_at=:coBankClosedAt, bet_window_open_at=:betWindowOpenAt,
            bet_window_close_at=:betWindowCloseAt, bet_window_close_message_id=:closeMsgId,
            bet_summary_text_msg_seq=:betSummaryText, bet_summary_image_msg_seq=:betSummaryImage,
            co_bank_summary_msg_seq=:coBankSummary,
            draw_locked_at=:drawLockedAt, settled_at=:settledAt, updated_at=NOW() WHERE id=:id
            """, new MapSqlParameterSource()
            .addValue("status", r.getStatus())
            .addValue("bankerUserId", r.getBankerUserId())
            .addValue("bankerDoor", r.getBankerDoor())
            .addValue("bankerLimit", r.getBankerLimit())
            .addValue("coBankClosedAt", r.getCoBankClosedAt()==null?null:Timestamp.from(r.getCoBankClosedAt()))
            .addValue("betWindowOpenAt", r.getBetWindowOpenAt()==null?null:Timestamp.from(r.getBetWindowOpenAt()))
            .addValue("betWindowCloseAt", r.getBetWindowCloseAt()==null?null:Timestamp.from(r.getBetWindowCloseAt()))
            .addValue("closeMsgId", r.getBetWindowCloseMessageId())
            .addValue("betSummaryText", r.getBetSummaryTextMsgSeq())
            .addValue("betSummaryImage", r.getBetSummaryImageMsgSeq())
            .addValue("coBankSummary", r.getCoBankSummaryMsgSeq())
            .addValue("drawLockedAt", r.getDrawLockedAt()==null?null:Timestamp.from(r.getDrawLockedAt()))
            .addValue("settledAt", r.getSettledAt()==null?null:Timestamp.from(r.getSettledAt()))
            .addValue("id", r.getId()));
    }
    public java.util.List<SangongRound> listSettledBySession(long sessionId){
        return jdbc.query("SELECT * FROM sangong_rounds WHERE tenant_id=:t AND session_id=:s AND status='settled' ORDER BY period_no, id",
            new MapSqlParameterSource("s",sessionId).addValue("t", com.chat99.sangong.tenant.TenantContext.require()), M);
    }
    public Optional<SangongRound> findLastSettledBySession(long sessionId){
        var list=jdbc.query("SELECT * FROM sangong_rounds WHERE tenant_id=:t AND session_id=:s AND status='settled' ORDER BY period_no DESC, id DESC LIMIT 1",
            new MapSqlParameterSource("s",sessionId).addValue("t", com.chat99.sangong.tenant.TenantContext.require()), M);
        return list.stream().findFirst();
    }
    public Optional<SangongRound> findLastSettled(){
        var list=jdbc.query(
            "SELECT * FROM sangong_rounds WHERE tenant_id=:t AND status='settled' ORDER BY id DESC LIMIT 1",
            new MapSqlParameterSource("t", com.chat99.sangong.tenant.TenantContext.require()), M);
        return list.stream().findFirst();
    }
    public Optional<SangongRound> findActiveByBanker(long userId){
        var list=jdbc.query(
            "SELECT * FROM sangong_rounds WHERE tenant_id=:t AND banker_user_id=:u AND status NOT IN ('settled','voided') ORDER BY id DESC LIMIT 1",
            new MapSqlParameterSource()
                .addValue("t", com.chat99.sangong.tenant.TenantContext.require())
                .addValue("u",userId), M);
        return list.stream().findFirst();
    }
    public Optional<SangongRound> findActiveByIds(List<Long> ids){
        if (ids.isEmpty()) return Optional.empty();
        var list=jdbc.query("SELECT * FROM sangong_rounds WHERE id IN (:ids) AND status NOT IN ('settled','voided') ORDER BY id DESC LIMIT 1",
            new MapSqlParameterSource("ids",ids), M);
        return list.stream().findFirst();
    }
}
