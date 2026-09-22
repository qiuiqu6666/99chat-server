package com.chat99.server.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConversationNotifyController {

    private static final Logger log = LoggerFactory.getLogger(ConversationNotifyController.class);

    private final ConversationNotifyService notifyService;

    public ConversationNotifyController(ConversationNotifyService notifyService) {
        this.notifyService = notifyService;
    }

    public record NotifyUpdateRequest(
        @NotBlank String chatType,
        @NotBlank String peerId,
        @NotNull Boolean muted) {}

    public record NotifyBatchRequest(@NotNull List<NotifyUpdateRequest> items) {}

    /** 设置单会话消息通知（免打扰）。客户端在 IM SDK 切换免打扰后须同步调用。 */
    @PutMapping("/me/conversation-notify")
    public Map<String, Object> update(@Valid @RequestBody NotifyUpdateRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        notifyService.setMuted(userId, req.chatType(), req.peerId(), req.muted());
        log.info("conversation notify updated userId={} chatType={} peerId={} muted={}",
            userId, req.chatType(), req.peerId(), req.muted());
        return Map.of("ok", true);
    }

    /** 批量同步免打扰（登录后或从 IM 拉取设置后调用）。 */
    @PutMapping("/me/conversation-notify/batch")
    public Map<String, Object> updateBatch(@Valid @RequestBody NotifyBatchRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        long started = System.currentTimeMillis();
        List<ConversationNotifyService.NotifySettingItem> items = req.items().stream()
            .map(i -> new ConversationNotifyService.NotifySettingItem(i.chatType(), i.peerId(), i.muted()))
            .toList();
        notifyService.setMutedBatch(userId, items);
        log.info("conversation notify batch updated userId={} count={} costMs={}",
            userId, items.size(), System.currentTimeMillis() - started);
        return Map.of("ok", true, "count", items.size());
    }

    /** 列出当前用户已免打扰的会话。 */
    @GetMapping("/me/conversation-notify")
    public List<ConversationNotifyService.NotifySettingView> listMuted(Authentication auth) {
        return notifyService.listMuted((String) auth.getPrincipal());
    }
}
