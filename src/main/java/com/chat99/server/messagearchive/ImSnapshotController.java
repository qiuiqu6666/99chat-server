package com.chat99.server.messagearchive;

import com.chat99.server.messagearchive.ImSnapshotModels.SnapshotResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "chat99.message-archive.enabled", havingValue = "true")
public class ImSnapshotController {

    private final ImSnapshotService snapshotService;
    private final ImSnapshotRateLimiter rateLimiter;

    public ImSnapshotController(ImSnapshotService snapshotService, ImSnapshotRateLimiter rateLimiter) {
        this.snapshotService = snapshotService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/im/snapshot")
    public SnapshotResponse snapshot(Authentication auth,
                                     @RequestParam(required = false) Integer limitConv,
                                     @RequestParam(required = false) Integer limitC2c,
                                     @RequestParam(required = false) Integer limitGroup,
                                     @RequestParam(required = false) Integer limitMsg) {
        String userId = (String) auth.getPrincipal();
        rateLimiter.check(userId);
        return snapshotService.build(userId, limitConv, limitC2c, limitGroup, limitMsg);
    }
}
