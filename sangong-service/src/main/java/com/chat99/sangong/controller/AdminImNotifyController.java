package com.chat99.sangong.controller;

import com.chat99.sangong.domain.SangongRound;
import com.chat99.sangong.service.ImService;
import com.chat99.sangong.service.RoundService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminImNotifyController {
    private final RoundService rounds;
    private final ImService im;

    public AdminImNotifyController(RoundService rounds, ImService im) {
        this.rounds = rounds;
        this.im = im;
    }

    @PostMapping({"/banker/send", "/rounds/current/banker/send"})
    public ResponseEntity<Map<String, Object>> sendBankerCurrent() {
        return sendBanker((String) null);
    }

    @PostMapping("/rounds/{id}/banker/send")
    public ResponseEntity<Map<String, Object>> sendBankerById(@PathVariable("id") String id) {
        return sendBanker(id);
    }

    private ResponseEntity<Map<String, Object>> sendBanker(String id) {
        SangongRound round = resolveRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "当前无可通知局");
        }
        RoundService.NotifyResult result;
        try {
            result = rounds.sendBankerNotify(round);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "NOTIFY_FAILED", e.getMessage());
        }
        SangongRound current = rounds.getCurrent();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sent", result.sent());
        out.put("restarted", result.restarted());
        out.put("newRound", result.newRound());
        out.put("round", current != null ? rounds.formatRound(current) : null);
        return ResponseEntity.ok(out);
    }

    @PostMapping({"/co-bank/send", "/rounds/current/co-bank/send"})
    public ResponseEntity<Map<String, Object>> sendCoBankCurrent() {
        return sendCoBank((String) null);
    }

    @PostMapping("/rounds/{id}/co-bank/send")
    public ResponseEntity<Map<String, Object>> sendCoBankById(@PathVariable("id") String id) {
        return sendCoBank(id);
    }

    private ResponseEntity<Map<String, Object>> sendCoBank(String id) {
        SangongRound round = resolveRound(id);
        if (round == null) {
            return PlayerBetController.err(404, "NOT_FOUND", "当前无可通知局");
        }
        boolean sent;
        try {
            sent = rounds.sendCoBankNotify(round);
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "NOTIFY_FAILED", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sent", sent);
        out.put("round", rounds.formatRound(round));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/im/send")
    public ResponseEntity<Map<String, Object>> sendCustom(@RequestBody(required = false) Map<String, Object> body) {
        String text = Req.str(body, "text", "").trim();
        if (text.isEmpty()) {
            return PlayerBetController.err(422, "INVALID_REQUEST", "消息内容不能为空");
        }
        boolean sent = im.sendGameGroupText(text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sent", sent);
        return ResponseEntity.ok(out);
    }

    private SangongRound resolveRound(String id) {
        if (id == null || "current".equals(id)) {
            return rounds.getCurrent();
        }
        try {
            return rounds.findById(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
