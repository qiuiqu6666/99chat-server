package com.chat99.sangong.service;

import com.chat99.sangong.common.TimeFmt;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongRoundDraw;
import com.chat99.sangong.repository.DrawRepository;
import com.chat99.sangong.repository.RoundRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 开彩录入与状态（与 PHP DrawService 一致）。 */
@Service
public class DrawService {
    private final DrawAmountParser amounts;
    private final HandTypeService hands;
    private final GameSettingsService settings;
    private final DrawRepository draws;
    private final RoundRepository rounds;
    private final RealtimeVersionStore realtime;

    public DrawService(DrawAmountParser amounts, HandTypeService hands, GameSettingsService settings,
                       DrawRepository draws, RoundRepository rounds, RealtimeVersionStore realtime) {
        this.amounts = amounts;
        this.hands = hands;
        this.settings = settings;
        this.draws = draws;
        this.rounds = rounds;
        this.realtime = realtime;
    }

    /** drawsInput: door => amountRaw */
    @Transactional
    public Map<String, Object> recordDraws(SangongRound roundRef, Map<Integer, String> drawsInput) {
        assertCanRecord(roundRef);
        if (drawsInput.isEmpty()) {
            throw new RuntimeException("请提供至少一门开彩金额");
        }
        SangongRound round = rounds.lockById(roundRef.getId());
        if (round == null) {
            throw new RuntimeException("局不存在");
        }
        assertCanRecord(round);
        for (Map.Entry<Integer, String> e : drawsInput.entrySet()) {
            saveOne(round, e.getKey(), e.getValue());
        }
        if (round.getDrawLockedAt() == null) {
            round.setDrawLockedAt(Instant.now());
            rounds.save(round);
        }
        realtime.touch();
        return buildStatus(rounds.findById(round.getId()).orElse(round));
    }

    public Map<String, Object> getStatus(SangongRound round) {
        return buildStatus(round);
    }

    private void saveOne(SangongRound round, int door, String amountRaw) {
        int doorCount = settings.getDoorCount();
        if (door < 1 || door > doorCount) {
            throw new RuntimeException("门号须在1-" + doorCount + "之间");
        }
        if (round.getBankerDoor() == null) {
            throw new RuntimeException("尚未定庄，不可录入开彩");
        }
        int hundredths = amounts.parse(amountRaw);
        HandTypeService.HandInfo hand = hands.analyze(hundredths);

        SangongRoundDraw draw = new SangongRoundDraw();
        draw.setRoundId(round.getId());
        draw.setDoor(door);
        draw.setAmountHundredths(hundredths);
        draw.setAmountRaw(amountRaw);
        draw.setHandType(hand.handType());
        draw.setHandLabel(hand.handLabel());
        draw.setPointValue(hand.pointValue());
        draw.setPairValue(hand.pairValue());
        draw.setCompareValue(hand.compareValue());
        draws.upsert(draw);
    }

    public List<Integer> getRequiredDoors(SangongRound round) {
        if (round.getBankerDoor() == null) {
            return List.of();
        }
        List<Integer> doors = new ArrayList<>();
        int doorCount = settings.getDoorCount();
        for (int door = 1; door <= doorCount; door++) {
            doors.add(door);
        }
        return doors;
    }

    private void assertCanRecord(SangongRound round) {
        if (SangongRound.SETTLED.equals(round.getStatus())) {
            throw new RuntimeException("本局已结算，不可录入开彩");
        }
        if (round.getBankerDoor() == null) {
            throw new RuntimeException("尚未定庄，不可录入开彩");
        }
        if (round.getBetWindowCloseAt() == null) {
            throw new RuntimeException("请先截止下注，再录入开彩");
        }
    }

    private Map<String, Object> buildStatus(SangongRound round) {
        List<Integer> required = getRequiredDoors(round);
        List<SangongRoundDraw> drawRows = draws.listByRound(round.getId());
        List<Integer> recorded = new ArrayList<>();
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (SangongRoundDraw draw : drawRows) {
            recorded.add(draw.getDoor());
            formatted.add(formatDraw(draw));
        }
        List<Integer> missing = new ArrayList<>(required);
        missing.removeAll(recorded);
        missing.sort(Integer::compareTo);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roundId", round.getId());
        out.put("periodNo", round.getPeriodNo());
        out.put("doorCount", settings.getDoorCount());
        out.put("bankerDoor", round.getBankerDoor());
        out.put("drawLockedAt", TimeFmt.iso(round.getDrawLockedAt()));
        out.put("requiredDoors", required);
        out.put("missingDoors", missing);
        out.put("complete", missing.isEmpty());
        out.put("draws", formatted);
        return out;
    }

    public String amountDisplay(SangongRoundDraw draw) {
        return amounts.format(draw.getAmountHundredths());
    }

    private Map<String, Object> formatDraw(SangongRoundDraw draw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("door", draw.getDoor());
        out.put("amount", amountDisplay(draw));
        out.put("amountHundredths", draw.getAmountHundredths());
        out.put("rawInput", draw.getAmountRaw());
        out.put("handType", draw.getHandType());
        out.put("handLabel", draw.getHandLabel());
        out.put("pointValue", draw.getPointValue());
        out.put("pairValue", draw.getPairValue());
        out.put("compareValue", draw.getCompareValue());
        return out;
    }
}
