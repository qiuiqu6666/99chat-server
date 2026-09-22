package com.chat99.server.integration;

import com.chat99.server.integration.ImExternalMessageRecallService.ExternalRecallResult;
import com.chat99.server.integration.ImExternalMessageRecallService.RecallRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/integration/v1/im/messages")
public class IntegrationImMessageRecallController {

    private final IntegrationAuthService authService;
    private final ImExternalMessageRecallService recallService;

    public IntegrationImMessageRecallController(IntegrationAuthService authService,
                                                ImExternalMessageRecallService recallService) {
        this.authService = authService;
        this.recallService = recallService;
    }

    @PostMapping("/recall")
    public ExternalRecallResult recall(
        @RequestHeader(value = "X-Integration-Token", required = false) String headerToken,
        @RequestHeader(value = "X-Sign", required = false) String sign,
        @RequestHeader(value = "X-Request-Time", required = false) String requestTime,
        @RequestBody RecallRequest body) {
        authService.verify(headerToken, sign, requestTime);
        if (body == null || body.chatType() == null || body.chatType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String chatType = body.chatType().trim().toLowerCase();
        boolean syncArchive = body.syncArchiveOrDefault();
        return switch (chatType) {
            case "c2c" -> recallService.recallC2c(
                body.fromAccount(), body.toAccount(), body.msgKey(), syncArchive);
            case "group" -> recallService.recallGroup(
                body.groupId(), body.msgSeqList(), body.reason(), syncArchive);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CHAT_TYPE");
        };
    }
}
