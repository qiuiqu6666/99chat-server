package com.chat99.server.integration;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration/v1/groups")
public class IntegrationGroupLiveMemberFilterController {

    private final IntegrationAuthService authService;
    private final IntegrationGroupLiveMemberFilterService filterService;

    public IntegrationGroupLiveMemberFilterController(
        IntegrationAuthService authService,
        IntegrationGroupLiveMemberFilterService filterService
    ) {
        this.authService = authService;
        this.filterService = filterService;
    }

    public record MemberFilterRequest(String userId, List<String> groupIds) {}

    @PostMapping("/live-member-filter")
    public Map<String, Object> filter(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) MemberFilterRequest body
    ) {
        authService.verify(headerToken, sign, requestTime);
        if (body == null) {
            return filterService.filter(null, List.of());
        }
        return filterService.filter(body.userId(), body.groupIds());
    }
}
