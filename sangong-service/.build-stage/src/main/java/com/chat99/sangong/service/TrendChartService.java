package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongRoundDraw;
import com.chat99.sangong.repository.DrawRepository;
import com.chat99.sangong.repository.RoundRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 走势图数据（与 PHP TrendChartService 对齐）。 */
@Service
public class TrendChartService {
    private static final int MAX_ROWS = 12;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final GameSettingsService settings;
    private final CompareService compare;
    private final RoundRepository rounds;
    private final DrawRepository draws;

    public TrendChartService(GameSettingsService settings, CompareService compare,
                             RoundRepository rounds, DrawRepository draws) {
        this.settings = settings;
        this.compare = compare;
        this.rounds = rounds;
        this.draws = draws;
    }

    public Map<String, Object> buildForSession(long sessionId) {
        int doorCount = settings.getDoorCount();
        List<SangongRound> settled = rounds.listSettledBySession(sessionId).stream()
            .filter(r -> r.getSettledAt() != null)
            .toList();
        if (settled.size() > MAX_ROWS) {
            settled = settled.subList(settled.size() - MAX_ROWS, settled.size());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SangongRound round : settled) {
            rows.add(buildRow(round, doorCount));
        }
        while (rows.size() < MAX_ROWS) {
            rows.add(buildEmptyRow(doorCount));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doorCount", doorCount);
        out.put("rows", rows);
        return out;
    }

    private Map<String, Object> buildEmptyRow(int doorCount) {
        Map<Integer, Map<String, Object>> doors = new LinkedHashMap<>();
        for (int door = 1; door <= doorCount; door++) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("amount", null);
            cell.put("compare", null);
            doors.put(door, cell);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("periodNo", null);
        row.put("time", "");
        row.put("bankerDoor", 0);
        row.put("placeholder", true);
        row.put("doors", doors);
        return row;
    }

    private Map<String, Object> buildRow(SangongRound round, int doorCount) {
        int bankerDoor = round.getBankerDoor() == null ? 0 : round.getBankerDoor();
        Map<Integer, SangongRoundDraw> drawRows = new LinkedHashMap<>();
        for (SangongRoundDraw d : draws.listByRound(round.getId())) {
            drawRows.put(d.getDoor(), d);
        }
        SangongRoundDraw bankerDraw = drawRows.get(bankerDoor);
        Map<Integer, Map<String, Object>> doors = new LinkedHashMap<>();
        for (int door = 1; door <= doorCount; door++) {
            doors.put(door, buildDoorCell(door, bankerDoor, drawRows.get(door), bankerDraw));
        }
        String time = round.getSettledAt() == null ? ""
            : HM.format(round.getSettledAt().atZone(ZONE));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("periodNo", round.getPeriodNo());
        row.put("time", time);
        row.put("bankerDoor", bankerDoor);
        row.put("doors", doors);
        return row;
    }

    private Map<String, Object> buildDoorCell(int door, int bankerDoor,
                                              SangongRoundDraw draw, SangongRoundDraw bankerDraw) {
        Map<String, Object> cell = new LinkedHashMap<>();
        if (draw == null) {
            cell.put("amount", null);
            cell.put("compare", null);
            return cell;
        }
        cell.put("amount", draw.amountDisplay());
        if (door == bankerDoor) {
            cell.put("compare", "banker");
            return cell;
        }
        if (bankerDraw != null) {
            cell.put("compare", compare.bankerWins(bankerDraw, draw) ? "banker" : "player");
        } else {
            cell.put("compare", null);
        }
        return cell;
    }
}
