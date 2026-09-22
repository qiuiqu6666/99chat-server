package com.chat99.server.integration;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration/v1/groups")
public class IntegrationGroupLiveNotifyController {

    private final IntegrationAuthService authService;
    private final IntegrationGroupLiveNotifyService notifyService;

    public IntegrationGroupLiveNotifyController(
        IntegrationAuthService authService,
        IntegrationGroupLiveNotifyService notifyService
    ) {
        this.authService = authService;
        this.notifyService = notifyService;
    }

    public record LiveChangedNotifyRequest(
        String groupId,
        String operatorUserId,
        String changeEventId,
        Map<String, Object> detail
    ) {}

    @PostMapping("/live-changed-notify")
    public Map<String, Object> notify(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) LiveChangedNotifyRequest body
    ) {
        authService.verify(headerToken, sign, requestTime);
        if (body == null) {
            return notifyService.notify(null, null, null, null);
        }
        return notifyService.notify(body.groupId(), body.operatorUserId(), body.changeEventId(), body.detail());
    }
}
