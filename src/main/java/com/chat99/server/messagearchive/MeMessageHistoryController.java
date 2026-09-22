package com.chat99.server.messagearchive;

import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class MeMessageHistoryController {

    private final MessageHistoryService historyService;
    private final MessageHistoryAuthorizationService authorizationService;
    private final MessageHistoryRateLimiter rateLimiter;
    private final ChatHistoryClearService clearService;
    private final MsgIdBackfillService msgIdBackfillService;

    public MeMessageHistoryController(MessageHistoryService historyService,
                                      MessageHistoryAuthorizationService authorizationService,
                                      MessageHistoryRateLimiter rateLimiter,
                                      ChatHistoryClearService clearService,
                                      MsgIdBackfillService msgIdBackfillService) {
        this.historyService = historyService;
        this.authorizationService = authorizationService;
        this.rateLimiter = rateLimiter;
        this.clearService = clearService;
        this.msgIdBackfillService = msgIdBackfillService;
    }

    @GetMapping("/me/messages/c2c")
    public MessageHistoryService.HistoryPage c2c(Authentication auth,
                                                 @RequestParam String peerUserId,
                                                 @RequestParam(required = false) Long cursor,
                                                 @RequestParam(required = false) Long fromTimeMs,
                                                 @RequestParam(required = false) Long toTimeMs,
                                                 @RequestParam(defaultValue = "30") int limit) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.check(userId);
        authorizationService.authorizeC2c(userId, peerUserId);
        return historyService.listC2c(userId, peerUserId, cursor, fromTimeMs, toTimeMs, limit);
    }

    @GetMapping("/me/messages/group")
    public MessageHistoryService.HistoryPage group(Authentication auth,
                                                   @RequestParam String groupId,
                                                   @RequestParam(required = false) Long cursor,
                                                   @RequestParam(required = false) Long fromSeq,
                                                   @RequestParam(required = false) Long toSeq,
                                                   @RequestParam(defaultValue = "30") int limit) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.check(userId);
        return historyService.listGroup(userId, groupId, cursor, fromSeq, toSeq, limit);
    }

    /**
     * 按 msgKey 解析腾讯 MsgId：先查归档，仍空则漫游补齐并回写（仅 C2C）。
     */
    @PostMapping("/me/messages/resolve-msg-ids")
    public Map<String, Object> resolveMsgIds(Authentication auth, @RequestBody ResolveMsgIdsRequest body) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.check(userId);
        if (body == null || body.msgKeys() == null || body.msgKeys().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (body.msgKeys().size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String chatType = body.chatType() == null ? "" : body.chatType().trim().toLowerCase();
        if (!"c2c".equals(chatType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ONLY_C2C_SUPPORTED");
        }
        if (body.peerId() == null || body.peerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        var items = msgIdBackfillService.resolveC2c(userId, body.peerId().trim(), body.msgKeys());
        return Map.of(
            "items", items.stream()
                .map(i -> Map.of(
                    "msgKey", i.msgKey(),
                    "msgId", i.msgId() == null ? "" : i.msgId()))
                .toList());
    }

    public record ResolveMsgIdsRequest(
        String chatType,
        String peerId,
        String groupId,
        List<String> msgKeys) {}

    @DeleteMapping("/me/messages/c2c")
    public ChatHistoryClearService.ClearResult clearC2c(Authentication auth,
                                                        @RequestParam String peerUserId) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.check(userId);
        authorizationService.authorizeC2c(userId, peerUserId);
        return clearService.clearC2c(userId, peerUserId);
    }

    @DeleteMapping("/me/messages/group")
    public ChatHistoryClearService.ClearResult clearGroup(Authentication auth,
                                                          @RequestParam String groupId) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.check(userId);
        return clearService.clearGroup(userId, groupId);
    }
}
