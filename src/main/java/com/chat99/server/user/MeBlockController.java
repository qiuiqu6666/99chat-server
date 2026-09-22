package com.chat99.server.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeBlockController {

    private final UserBlockService blockService;
    private final FriendRequestService friendRequestService;

    public MeBlockController(UserBlockService blockService, FriendRequestService friendRequestService) {
        this.blockService = blockService;
        this.friendRequestService = friendRequestService;
    }

    public record BlockPayload(@NotBlank String userId) {}

    @PostMapping("/me/blocks")
    public Map<String, Object> block(Authentication auth, @Valid @RequestBody BlockPayload body) {
        String me = (String) auth.getPrincipal();
        String target = body.userId().trim();
        blockService.block(me, target);
        friendRequestService.rejectPendingBetween(me, target);
        return Map.of("ok", true, "userId", target);
    }

    @DeleteMapping("/me/blocks/{userId}")
    public Map<String, Object> unblock(Authentication auth, @PathVariable String userId) {
        String me = (String) auth.getPrincipal();
        blockService.unblock(me, userId);
        return Map.of("ok", true, "userId", userId);
    }

    @GetMapping("/me/blocks")
    public UserBlockService.BlockListResponse list(Authentication auth,
                                                   @RequestParam(defaultValue = "0") int startIndex,
                                                   @RequestParam(defaultValue = "50") int limit) {
        String me = (String) auth.getPrincipal();
        return blockService.list(me, startIndex, limit);
    }
}
