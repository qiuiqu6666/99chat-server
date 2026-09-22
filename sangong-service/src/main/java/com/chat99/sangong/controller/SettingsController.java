package com.chat99.sangong.controller;

import com.chat99.sangong.service.GameSettingsService;
import com.chat99.sangong.service.RealtimeVersionStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final GameSettingsService settings;
    private final RealtimeVersionStore realtime;

    public SettingsController(GameSettingsService settings, RealtimeVersionStore realtime) {
        this.settings = settings;
        this.realtime = realtime;
    }

    @GetMapping
    public Map<String, Object> show() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("settings", settings.all());
        return out;
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> updated;
        try {
            updated = settings.update(Req.body(body));
        } catch (RuntimeException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("code", "INVALID_SETTINGS");
            err.put("message", e.getMessage());
            return ResponseEntity.status(422).body(err);
        }
        realtime.touch();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "游戏规则已保存，建议下一局生效");
        out.put("settings", updated);
        return ResponseEntity.ok(out);
    }
}
