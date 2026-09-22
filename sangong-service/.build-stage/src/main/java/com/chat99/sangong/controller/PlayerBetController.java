package com.chat99.sangong.controller;

import com.chat99.sangong.common.InsufficientBalanceException;
import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.domain.SangongUser;
import com.chat99.sangong.service.BetService;
import com.chat99.sangong.service.BetTextParser;
import com.chat99.sangong.service.GameSettingsService;
import com.chat99.sangong.service.RoundService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlayerBetController {
    private static final String FORMAT_HINT = "请使用 门+金额 格式，如 1.200、1；200、1（200）、234/200、全200、公2000";

    private final BetService bets;
    private final BetTextParser betTextParser;
    private final RoundService rounds;
    private final GameSettingsService settings;

    public PlayerBetController(BetService bets, BetTextParser betTextParser,
                               RoundService rounds, GameSettingsService settings) {
        this.bets = bets;
        this.betTextParser = betTextParser;
        this.rounds = rounds;
        this.settings = settings;
    }

    @PostMapping("/api/v1/bets")
    public ResponseEntity<Map<String, Object>> store(HttpServletRequest request,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        SangongUser user = (SangongUser) request.getAttribute("auth_user");
        String text = Req.str(body, "text", "");

        if (!text.isEmpty()) {
            return storeByText(user, text);
        }

        int door = Req.intval(body, "door", 0);
        long amount = Req.lng(body, "amount", 0);
        if (door < 1 || amount <= 0) {
            return err(422, "INVALID_BET", "门号与金额须为正整数");
        }

        Map<String, Object> result;
        try {
            result = bets.placeBet(user, door, amount, false);
        } catch (InsufficientBalanceException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("code", "INSUFFICIENT_BALANCE");
            out.put("door", door);
            out.put("amount", amount);
            out.put("balance", e.getBalance());
            out.put("message", "余额不足，请联系管理员上分或代录");
            return ResponseEntity.status(422).body(out);
        } catch (RuntimeException e) {
            return err(422, "BET_REJECTED", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("door", result.get("door"));
        out.put("amount", result.get("amount"));
        out.put("doorTotal", result.get("doorTotal"));
        out.put("balance", result.get("balance"));
        out.put("roundId", result.get("roundId"));
        out.put("periodNo", result.get("periodNo"));
        return ResponseEntity.status(201).body(out);
    }

    private ResponseEntity<Map<String, Object>> storeByText(SangongUser user, String text) {
        SangongRound round = rounds.getCurrent();
        Integer bankerDoor = round != null ? round.getBankerDoor() : null;
        int doorCount = settings.getDoorCount();

        BetTextParser.Parsed parsed = betTextParser.parseAndResolve(text, doorCount, bankerDoor);
        if (parsed == null) {
            BetTextParser.Parsed raw = betTextParser.parse(text);
            String message;
            if (raw != null) {
                message = betTextParser.resolveRejectReason(raw, doorCount, bankerDoor);
                if ("庄门不可下注".equals(message)) {
                    message = "压到庄包";
                } else if ("下注指令无效".equals(message)) {
                    message = FORMAT_HINT;
                }
            } else {
                message = FORMAT_HINT;
            }
            String code = raw != null && betTextParser.touchesOnlyBankerDoor(raw, doorCount, bankerDoor)
                ? "BANKER_DOOR" : "INVALID_BET_FORMAT";
            return err(422, code, message);
        }

        boolean allIdle = parsed.isAllIdle();
        Map<String, Object> result;
        try {
            result = bets.placeMultiBet(user, parsed.doors(), parsed.amount(), false, null, false,
                allIdle, betTextParser.allIdleKeyword(parsed));
        } catch (InsufficientBalanceException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("code", "INSUFFICIENT_BALANCE");
            out.put("doors", parsed.doors());
            out.put("amount", parsed.amount());
            out.put("totalAmount", (long) parsed.amount() * parsed.doors().size());
            out.put("allIdle", allIdle);
            out.put("balance", e.getBalance());
            out.put("message", "余额不足，请联系管理员上分或代录");
            return ResponseEntity.status(422).body(out);
        } catch (RuntimeException e) {
            return err(422, "BET_REJECTED", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("doors", result.get("doors"));
        out.put("amount", result.get("amount"));
        out.put("totalAmount", result.get("totalAmount"));
        out.put("allIdle", allIdle);
        out.put("betIds", result.get("betIds"));
        out.put("balance", result.get("balance"));
        out.put("roundId", result.get("roundId"));
        out.put("periodNo", result.get("periodNo"));
        return ResponseEntity.status(201).body(out);
    }

    static ResponseEntity<Map<String, Object>> err(int status, String code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("code", code);
        out.put("message", message);
        return ResponseEntity.status(status).body(out);
    }
}
