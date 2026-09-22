package com.chat99.server.messagearchive;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class SuperGroupHistoryController {
    private final SuperGroupHistoryService service;
    private final MessageHistoryRateLimiter rateLimiter;
    public SuperGroupHistoryController(SuperGroupHistoryService service,
                                       MessageHistoryRateLimiter rateLimiter) {
        this.service = service; this.rateLimiter = rateLimiter;
    }
    @GetMapping("/groups/{groupId}/messages/history")
    public SuperGroupHistoryService.HistoryResponse history(Authentication auth,
        @PathVariable String groupId,
        @RequestParam(defaultValue = "older") String direction,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(required = false) String cursor) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.check(userId);
        if (!"older".equalsIgnoreCase(direction)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_DIRECTION");
        }
        return service.older(userId, groupId, limit, cursor);
    }
}
