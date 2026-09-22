package com.chat99.sangong.repository;

import com.chat99.sangong.domain.SangongRoundDraw;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DrawRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final RowMapper<SangongRoundDraw> M = (rs, i) -> {
        SangongRoundDraw d = new SangongRoundDraw();
        d.setId(rs.getLong("id"));
        d.setRoundId(rs.getLong("round_id"));
        d.setDoor(rs.getInt("door"));
        d.setAmountHundredths(rs.getInt("amount_hundredths"));
        d.setAmountRaw(rs.getString("raw_input"));
        d.setHandType(rs.getString("hand_type"));
        d.setHandLabel(rs.getString("hand_label"));
        int pv = rs.getInt("point_value");
        d.setPointValue(rs.wasNull() ? null : pv);
        int pr = rs.getInt("pair_value");
        d.setPairValue(rs.wasNull() ? null : pr);
        d.setCompareValue(rs.getInt("compare_value"));
        return d;
    };
    public DrawRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<SangongRoundDraw> listByRound(long roundId) {
        return jdbc.query("SELECT * FROM sangong_round_draws WHERE round_id=:r ORDER BY door",
            new MapSqlParameterSource("r", roundId), M);
    }
    public Optional<SangongRoundDraw> findByRoundAndDoor(long roundId, int door) {
        var list = jdbc.query("SELECT * FROM sangong_round_draws WHERE round_id=:r AND door=:d",
            new MapSqlParameterSource().addValue("r", roundId).addValue("d", door), M);
        return list.stream().findFirst();
    }
    public void upsert(SangongRoundDraw d) {
        var params = new MapSqlParameterSource()
            .addValue("r", d.getRoundId()).addValue("d", d.getDoor())
            .addValue("ah", d.getAmountHundredths()).addValue("raw", d.getAmountRaw())
            .addValue("ht", d.getHandType()).addValue("hl", d.getHandLabel())
            .addValue("pv", d.getPointValue()).addValue("pr", d.getPairValue())
            .addValue("cv", d.getCompareValue());
        int updated = jdbc.update("""
            UPDATE sangong_round_draws SET amount_hundredths=:ah, raw_input=:raw, hand_type=:ht, hand_label=:hl,
            point_value=:pv, pair_value=:pr, compare_value=:cv, updated_at=NOW() WHERE round_id=:r AND door=:d
            """, params);
        if (updated == 0) {
            jdbc.update("""
                INSERT INTO sangong_round_draws(round_id,door,amount_hundredths,raw_input,hand_type,hand_label,point_value,pair_value,compare_value)
                VALUES(:r,:d,:ah,:raw,:ht,:hl,:pv,:pr,:cv)
                """, params);
        }
    }
    public void deleteByRound(long roundId) {
        jdbc.update("DELETE FROM sangong_round_draws WHERE round_id=:r", new MapSqlParameterSource("r", roundId));
    }
    public boolean existsByRound(long roundId) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM sangong_round_draws WHERE round_id=:r",
            new MapSqlParameterSource("r", roundId), Integer.class);
        return c != null && c > 0;
    }
}
