package com.chat99.server.chatattachment;

import com.chat99.server.integration.IntegrationAuthService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatAttachmentImEventController {

    private final IntegrationAuthService authService;
    private final ChatAttachmentImBindService bindService;

    public ChatAttachmentImEventController(IntegrationAuthService authService,
                                           ChatAttachmentImBindService bindService) {
        this.authService = authService;
        this.bindService = bindService;
    }

    @PostMapping("/internal/chat/attachments/im-events")
    public Map<String, Object> ingest(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody(required = false) Map<String, Object> body) {
        authService.verify(headerToken, sign, requestTime);
        bindService.handleAfterSend(body == null ? Map.of() : body);
        return bindService.ok();
    }
}
