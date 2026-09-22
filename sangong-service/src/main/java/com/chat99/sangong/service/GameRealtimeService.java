package com.chat99.sangong.service;

import com.chat99.sangong.config.SangongProperties;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.repository.BetRepository;
import com.chat99.sangong.repository.RoundRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 实时快照（SSE / 轮询用），与 PHP GameRealtimeService 一致。 */
@Service
public class GameRealtimeService {
    private final SessionService sessions;
    private final DrawService draws;
    private final BetService bets;
    private final ImMessageService imMessages;
    private final GameSettingsService settings;
    private final BetRepository betRepo;
    private final RoundRepository roundRepo;
    private final RealtimeVersionStore versionStore;
    private final SangongProperties props;

    public GameRealtimeService(SessionService sessions, DrawService draws, BetService bets,
                               ImMessageService imMessages, GameSettingsService settings,
                               BetRepository betRepo, RoundRepository roundRepo,
                               RealtimeVersionStore versionStore, SangongProperties props) {
        this.sessions = sessions;
        this.draws = draws;
        this.bets = bets;
        this.imMessages = imMessages;
        this.settings = settings;
        this.betRepo = betRepo;
        this.roundRepo = roundRepo;
        this.versionStore = versionStore;
        this.props = props;
    }

    public long getVersion() {
        return versionStore.getVersion();
    }

    public int getPollIntervalMs() {
        int ms = props.getRealtime().getPollMs();
        return Math.max(100, Math.min(5000, ms));
    }

    public int getHeartbeatSeconds() {
        int sec = props.getRealtime().getHeartbeatSeconds();
        return Math.max(5, Math.min(120, sec));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> buildSnapshot() {
        Map<String, Object> status = sessions.getStatus();
        SangongRound round = null;
        Map<String, Object> roundData = (Map<String, Object>) status.get("round");
        if (roundData != null && roundData.get("id") instanceof Number id) {
            round = roundRepo.findById(id.longValue()).orElse(null);
        }

        int doorCount = settings.getDoorCount();
        Map<String, Object> pending = round != null
            ? imMessages.getPendingDoorStats(round) : emptyPendingStats(doorCount);
        Map<String, Object> placed = round != null
            ? buildPlacedStats(round, doorCount) : emptyPlacedStats(doorCount);

        Map<String, Object> draw = null;
        if (round != null && round.getBetWindowCloseAt() != null) {
            draw = draws.getStatus(round);
        }

        Map<String, Object> rules = settings.all();
        Map<String, Object> settingsOut = new LinkedHashMap<>();
        settingsOut.put("doorCount", doorCount);
        settingsOut.put("minBet", parseLong(rules.get("minBet")));
        settingsOut.put("maxBet", parseLong(rules.get("maxBet")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", getVersion());
        out.put("at", com.chat99.sangong.common.TimeFmt.iso(Instant.now()));
        out.put("status", status.get("status"));
        out.put("session", status.get("session"));
        out.put("round", status.get("round"));
        out.put("settings", settingsOut);
        out.put("draw", draw);
        out.put("pending", pending);
        out.put("placed", placed);
        return out;
    }

    private Map<String, Object> emptyPendingStats(int doorCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open", false);
        out.put("messageCount", 0);
        out.put("doorTotals", normalizeDoorTotals(Map.of(), doorCount));
        out.put("grandTotal", 0L);
        return out;
    }

    private Map<String, Object> emptyPlacedStats(int doorCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doorTotals", normalizeDoorTotals(Map.of(), doorCount));
        out.put("grandTotal", 0L);
        out.put("betCount", 0L);
        return out;
    }

    private Map<String, Object> buildPlacedStats(SangongRound round, int doorCount) {
        Map<Integer, Long> doorTotals = normalizeDoorTotals(bets.getDoorTotals(round.getId()), doorCount);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doorTotals", doorTotals);
        out.put("grandTotal", doorTotals.values().stream().mapToLong(Long::longValue).sum());
        out.put("betCount", betRepo.countByRound(round.getId()));
        return out;
    }

    private Map<Integer, Long> normalizeDoorTotals(Map<Integer, Long> totals, int doorCount) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        for (int door = 1; door <= doorCount; door++) {
            out.put(door, totals.getOrDefault(door, 0L));
        }
        return out;
    }

    private static long parseLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
