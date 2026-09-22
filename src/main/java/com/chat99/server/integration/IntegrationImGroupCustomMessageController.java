package com.chat99.server.integration;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/integration/v1/im")
public class IntegrationImGroupCustomMessageController {

    private final IntegrationAuthService authService;
    private final IntegrationImGroupCustomMessageService messageService;

    public IntegrationImGroupCustomMessageController(IntegrationAuthService authService,
                                                     IntegrationImGroupCustomMessageService messageService) {
        this.authService = authService;
        this.messageService = messageService;
    }

    public record GroupCustomMessageRequest(String groupId, Map<String, Object> payload) {}

    @PostMapping("/group-custom-message")
    public Map<String, Object> send(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) GroupCustomMessageRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (body == null) {
            return messageService.send(null, null);
        }
        return messageService.send(body.groupId(), body.payload());
    }
}
