package com.chat99.server.integration;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration/v1/admin/group-live")
public class IntegrationAdminGroupLiveCheckController {

    private final IntegrationAuthService authService;
    private final IntegrationAdminGroupLiveCheckService checkService;

    public IntegrationAdminGroupLiveCheckController(
        IntegrationAuthService authService,
        IntegrationAdminGroupLiveCheckService checkService
    ) {
        this.authService = authService;
        this.checkService = checkService;
    }

    @PostMapping("/check")
    public Map<String, Object> check(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authService.verify(headerToken, sign, requestTime);
        return checkService.check(authorization);
    }
}
