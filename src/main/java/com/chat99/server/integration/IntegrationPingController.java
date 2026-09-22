package com.chat99.server.integration;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration/v1")
public class IntegrationPingController {

    private final IntegrationAuthService authService;

    public IntegrationPingController(IntegrationAuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime) {
        authService.verify(headerToken, sign, requestTime);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("service", "99chat-server");
        return out;
    }
}
