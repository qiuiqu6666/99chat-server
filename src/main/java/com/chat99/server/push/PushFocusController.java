package com.chat99.server.push;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PushFocusController {

    private static final Logger log = LoggerFactory.getLogger(PushFocusController.class);

    private final PushFocusService pushFocusService;

    public PushFocusController(PushFocusService pushFocusService) {
        this.pushFocusService = pushFocusService;
    }

    public record PushFocusRequest(
        @NotBlank String chatType,
        String peerId,
        String groupId) {}

    /**
     * 上报用户当前正在查看的会话；TTL 90s，客户端须在会话内周期续期。
     * 服务端对该会话跳过聊天离线 Push。
     */
    @PutMapping("/me/push-focus")
    public Map<String, Object> setFocus(@RequestBody PushFocusRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        pushFocusService.setFocus(userId, req.chatType(), req.peerId(), req.groupId());
        log.debug("push focus set userId={} chatType={} peerId={} groupId={}",
            userId, req.chatType(), req.peerId(), req.groupId());
        return Map.of("ok", true);
    }

    /** 离开会话时清除 focus。 */
    @DeleteMapping("/me/push-focus")
    public Map<String, Object> clearFocus(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        pushFocusService.clearFocus(userId);
        log.debug("push focus cleared userId={}", userId);
        return Map.of("ok", true);
    }
}
