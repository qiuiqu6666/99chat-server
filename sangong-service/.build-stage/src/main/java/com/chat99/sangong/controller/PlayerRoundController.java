package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.service.BetService;
import com.chat99.sangong.service.RoundService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlayerRoundController {
    private final RoundService rounds;
    private final BetService bets;

    public PlayerRoundController(RoundService rounds, BetService bets) {
        this.rounds = rounds;
        this.bets = bets;
    }

    @GetMapping("/api/v1/rounds/current")
    public Map<String, Object> current() {
        SangongRound round = rounds.getCurrent();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        if (round == null) {
            out.put("round", null);
            return out;
        }
        out.put("round", rounds.formatRound(round));
        out.put("doorTotals", bets.getDoorTotals(round.getId()));
        return out;
    }
}
