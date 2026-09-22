package com.chat99.sangong.controller;

import com.chat99.sangong.service.SessionService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSessionController {
    private final SessionService sessions;

    public AdminSessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/session")
    public Map<String, Object> show() {
        Map<String, Object> status = sessions.getStatus();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("status", status.get("status"));
        out.put("session", status.get("session"));
        out.put("round", status.get("round"));
        return out;
    }

    @GetMapping("/sessions")
    public Map<String, Object> history(@RequestParam(value = "limit", defaultValue = "30") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessions", sessions.listRecent(limit));
        return out;
    }

    @PostMapping("/session/start")
    public ResponseEntity<Map<String, Object>> start() {
        Map<String, Object> status;
        try {
            sessions.start();
            status = sessions.getStatus();
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "SESSION_START_FAILED", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "开机成功");
        out.put("session", status.get("session"));
        out.put("round", status.get("round"));
        return ResponseEntity.status(201).body(out);
    }

    @PostMapping("/session/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        Map<String, Object> result;
        Map<String, Object> status;
        try {
            result = sessions.stop();
            status = sessions.getStatus();
        } catch (RuntimeException e) {
            return PlayerBetController.err(422, "SESSION_STOP_FAILED", e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "关机成功");
        out.put("status", status.get("status"));
        if (result.get("voidedRound") != null) {
            out.put("voidedRound", result.get("voidedRound"));
        }
        out.put("rebateUserCount", result.getOrDefault("rebateUserCount", 0));
        out.put("rebateTotal", result.getOrDefault("rebateTotal", 0));
        out.put("agentRebateTotal", result.getOrDefault("agentRebateTotal", 0));
        return ResponseEntity.ok(out);
    }
}
