package com.chat99.sangong.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {
    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "sangong-api");
        out.put("docs", "../sangong-game-concept.md");
        return out;
    }

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", "sangong-api");
        out.put("status", "ok");
        out.put("version", "0.1.0");
        return out;
    }
}
