package com.chat99.server.integration;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration/v1/calls")
public class IntegrationCallOpenSessionController {

    private final IntegrationAuthService authService;
    private final IntegrationCallOpenSessionService sessionService;

    public IntegrationCallOpenSessionController(IntegrationAuthService authService,
                                                IntegrationCallOpenSessionService sessionService) {
        this.authService = authService;
        this.sessionService = sessionService;
    }

    @GetMapping("/open-session")
    public Map<String, Object> openSession(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestParam(value = "userId", required = false) String userId) {
        authService.verify(headerToken, sign, requestTime);
        return sessionService.openSession(userId);
    }
}
