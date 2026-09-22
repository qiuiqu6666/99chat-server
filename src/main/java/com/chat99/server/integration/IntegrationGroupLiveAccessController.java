package com.chat99.server.integration;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration/v1/groups")
public class IntegrationGroupLiveAccessController {

    private final IntegrationAuthService authService;
    private final IntegrationGroupLiveAccessService accessService;

    public IntegrationGroupLiveAccessController(IntegrationAuthService authService,
                                                IntegrationGroupLiveAccessService accessService) {
        this.authService = authService;
        this.accessService = accessService;
    }

    public record LiveAccessRequest(String groupId, String userId, String requiredRole) {}

    @PostMapping("/live-access")
    public Map<String, Object> liveAccess(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) LiveAccessRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (body == null) {
            return accessService.check(null, null, null);
        }
        return accessService.check(body.groupId(), body.userId(), body.requiredRole());
    }
}
