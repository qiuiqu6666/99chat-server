package com.chat99.server.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConversationPinController {

    private static final Logger log = LoggerFactory.getLogger(ConversationPinController.class);

    private final ConversationPinService pinService;

    public ConversationPinController(ConversationPinService pinService) {
        this.pinService = pinService;
    }

    public record PinUpdateRequest(
        @NotBlank String chatType,
        @NotBlank String peerId,
        @NotNull Boolean pinned) {}

    public record PinBatchRequest(@NotNull List<PinUpdateRequest> items) {}

    /** 一次返回当前用户全部置顶会话。 */
    @GetMapping("/me/pinned-conversations")
    public ConversationPinService.PinListResponse list(Authentication auth) {
        return pinService.list((String) auth.getPrincipal());
    }

    /** 单条置顶 / 取消（幂等）；响应含变更后全量 items。 */
    @PutMapping("/me/pinned-conversations")
    public ConversationPinService.PinMutationResponse update(
        @Valid @RequestBody PinUpdateRequest req,
        Authentication auth) {
        String userId = (String) auth.getPrincipal();
        ConversationPinService.PinMutationResponse resp = pinService.setPinned(
            userId, req.chatType(), req.peerId(), req.pinned());
        log.info("conversation pin updated userId={} chatType={} peerId={} pinned={} count={}",
            userId, resp.chatType(), resp.peerId(), resp.pinned(), resp.items().size());
        return resp;
    }

    /** 批量置顶 / 取消（原子事务，最多 100 条）。 */
    @PutMapping("/me/pinned-conversations/batch")
    public ConversationPinService.PinBatchResponse updateBatch(
        @Valid @RequestBody PinBatchRequest req,
        Authentication auth) {
        String userId = (String) auth.getPrincipal();
        List<ConversationPinService.PinSettingItem> items = req.items().stream()
            .map(i -> new ConversationPinService.PinSettingItem(i.chatType(), i.peerId(), i.pinned()))
            .toList();
        ConversationPinService.PinBatchResponse resp = pinService.setPinnedBatch(userId, items);
        log.info("conversation pin batch updated userId={} count={} pinnedTotal={}",
            userId, resp.count(), resp.items().size());
        return resp;
    }
}
