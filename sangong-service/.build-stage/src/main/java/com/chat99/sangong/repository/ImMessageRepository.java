package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongImMessage;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ImMessageRepository {
    /** msg_seq 升序（无 seq 置后），同 seq 按 id 升序 —— 与 PHP BetWindowMessageOrder 一致 */
    public static final String ORDER_SQL = " ORDER BY CASE WHEN msg_seq IS NULL THEN 1 ELSE 0 END, msg_seq, id";

    private final NamedParameterJdbcTemplate jdbc;
    private static final RowMapper<SangongImMessage> M = (rs, i) -> {
        SangongImMessage m = new SangongImMessage();
        m.setId(rs.getLong("id"));
        long r = rs.getLong("round_id");
        m.setRoundId(rs.wasNull() ? null : r);
        m.setGroupId(rs.getString("group_id"));
        m.setImUserId(rs.getString("im_user_id"));
        m.setNickname(rs.getString("nickname"));
        long seq = rs.getLong("msg_seq");
        m.setMsgSeq(rs.wasNull() ? null : seq);
        m.setMsgId(rs.getString("msg_id"));
        long mt = rs.getLong("msg_time");
        m.setMsgTime(rs.wasNull() ? null : mt);
        m.setText(rs.getString("text"));
        m.setCallbackCommand(rs.getString("callback_command"));
        m.setRawPayload(rs.getString("raw_payload"));
        m.setOutcome(rs.getString("outcome"));
        m.setOutcomeDetail(rs.getString("outcome_detail"));
        long b = rs.getLong("bet_id");
        m.setBetId(rs.wasNull() ? null : b);
        Timestamp c = rs.getTimestamp("created_at");
        m.setCreatedAt(c == null ? null : c.toInstant());
        return m;
    };
    public ImMessageRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<SangongImMessage> findById(long id) {
        var list = jdbc.query("SELECT * FROM sangong_im_messages WHERE id=:id", new MapSqlParameterSource("id", id), M);
        return list.stream().findFirst();
    }
    public Optional<SangongImMessage> findByGroupAndSeq(String groupId, long msgSeq) {
        var list = jdbc.query("SELECT * FROM sangong_im_messages WHERE group_id=:g AND msg_seq=:s",
            new MapSqlParameterSource().addValue("g", groupId).addValue("s", msgSeq), M);
        return list.stream().findFirst();
    }
    public List<SangongImMessage> listByRoundOrdered(long roundId) {
        return jdbc.query("SELECT * FROM sangong_im_messages WHERE round_id=:r" + ORDER_SQL,
            new MapSqlParameterSource("r", roundId), M);
    }
    public List<SangongImMessage> listByRoundAndOutcomesOrdered(long roundId, List<String> outcomes) {
        return jdbc.query("SELECT * FROM sangong_im_messages WHERE round_id=:r AND outcome IN (:o)" + ORDER_SQL,
            new MapSqlParameterSource().addValue("r", roundId).addValue("o", outcomes), M);
    }
    public Optional<SangongImMessage> findLatestWithSeq(long roundId) {
        var list = jdbc.query("SELECT * FROM sangong_im_messages WHERE round_id=:r AND msg_seq IS NOT NULL ORDER BY msg_seq DESC, id DESC LIMIT 1",
            new MapSqlParameterSource("r", roundId), M);
        return list.stream().findFirst();
    }
    public Optional<SangongImMessage> findLatestById(long roundId) {
        var list = jdbc.query("SELECT * FROM sangong_im_messages WHERE round_id=:r ORDER BY id DESC LIMIT 1",
            new MapSqlParameterSource("r", roundId), M);
        return list.stream().findFirst();
    }
    public Optional<SangongImMessage> findByRoundAndSeq(long roundId, long msgSeq) {
        var list = jdbc.query("SELECT * FROM sangong_im_messages WHERE round_id=:r AND msg_seq=:s ORDER BY id DESC LIMIT 1",
            new MapSqlParameterSource().addValue("r", roundId).addValue("s", msgSeq), M);
        return list.stream().findFirst();
    }
    public long insert(SangongImMessage m) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        jdbc.update("""
            INSERT INTO sangong_im_messages(round_id,group_id,im_user_id,nickname,msg_seq,msg_id,msg_time,text,callback_command,raw_payload,outcome,outcome_detail,bet_id)
            VALUES(:roundId,:groupId,:imUserId,:nickname,:msgSeq,:msgId,:msgTime,:text,:cmd,:raw,:outcome,:detail,:betId)
            """, new MapSqlParameterSource()
            .addValue("roundId", m.getRoundId())
            .addValue("groupId", m.getGroupId())
            .addValue("imUserId", m.getImUserId())
            .addValue("nickname", m.getNickname())
            .addValue("msgSeq", m.getMsgSeq())
            .addValue("msgId", m.getMsgId())
            .addValue("msgTime", m.getMsgTime())
            .addValue("text", m.getText())
            .addValue("cmd", m.getCallbackCommand() == null ? "" : m.getCallbackCommand())
            .addValue("raw", m.getRawPayload() == null ? "{}" : m.getRawPayload())
            .addValue("outcome", m.getOutcome() == null ? SangongImMessage.OUTCOME_STORED : m.getOutcome())
            .addValue("detail", m.getOutcomeDetail())
            .addValue("betId", m.getBetId()), kh, new String[]{"id"});
        long id = kh.getKey().longValue();
        m.setId(id);
        return id;
    }
    public void updateOutcome(long id, String outcome, String detail, Long betId) {
        jdbc.update("UPDATE sangong_im_messages SET outcome=:o, outcome_detail=:d, bet_id=:b WHERE id=:id",
            new MapSqlParameterSource().addValue("o", outcome).addValue("d", detail).addValue("b", betId).addValue("id", id));
    }
    public void updateRoundId(long id, Long roundId) {
        jdbc.update("UPDATE sangong_im_messages SET round_id=:r WHERE id=:id",
            new MapSqlParameterSource().addValue("r", roundId).addValue("id", id));
    }
    public int detachRound(long roundId, String detail) {
        return jdbc.update("UPDATE sangong_im_messages SET round_id=NULL, outcome='ignored', outcome_detail=:d, bet_id=NULL WHERE round_id=:r",
            new MapSqlParameterSource().addValue("d", detail).addValue("r", roundId));
    }
    public int markOutcomeForIds(List<Long> ids, String outcome, String detail) {
        if (ids.isEmpty()) return 0;
        return jdbc.update("UPDATE sangong_im_messages SET outcome=:o, outcome_detail=:d WHERE id IN (:ids)",
            new MapSqlParameterSource().addValue("o", outcome).addValue("d", detail).addValue("ids", ids));
    }
    public int markPendingIgnored(long roundId, List<Long> ids, String detail) {
        if (ids.isEmpty()) return 0;
        return jdbc.update("""
            UPDATE sangong_im_messages SET outcome='ignored', outcome_detail=:d
            WHERE round_id=:r AND id IN (:ids)
              AND outcome IN ('pending_sufficient','pending_insufficient','pending_rejected')
            """, new MapSqlParameterSource().addValue("d", detail).addValue("r", roundId).addValue("ids", ids));
    }
    public int markAllPendingIgnored(long roundId, String detail) {
        return jdbc.update("""
            UPDATE sangong_im_messages SET outcome='ignored', outcome_detail=:d
            WHERE round_id=:r AND outcome IN ('pending_sufficient','pending_insufficient','pending_rejected')
            """, new MapSqlParameterSource().addValue("d", detail).addValue("r", roundId));
    }
    public Optional<SangongImMessage> findRecallTarget(String groupId, long msgSeq) {
        return findByGroupAndSeq(groupId, msgSeq);
    }
    public void deleteById(long id) {
        jdbc.update("DELETE FROM sangong_im_messages WHERE id=:id", new MapSqlParameterSource("id", id));
    }
}
