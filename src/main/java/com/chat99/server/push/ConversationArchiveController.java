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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConversationArchiveController {

    private static final Logger log = LoggerFactory.getLogger(ConversationArchiveController.class);

    private final ConversationArchiveService archiveService;

    public ConversationArchiveController(ConversationArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    public record ArchiveUpdateRequest(
        @NotBlank String chatType,
        @NotBlank String peerId,
        @NotNull Boolean archived) {}

    public record ArchiveBatchRequest(@NotNull List<ArchiveUpdateRequest> items) {}

    /** 列出当前用户已归档会话（支持 since/limit 增量）。 */
    @GetMapping("/me/archived-conversations")
    public ConversationArchiveService.ArchiveListResponse list(
        Authentication auth,
        @RequestParam(required = false) Long since,
        @RequestParam(required = false) Integer limit) {
        return archiveService.list((String) auth.getPrincipal(), since, limit);
    }

    /** 单条归档 / 取消归档（幂等）。 */
    @PutMapping("/me/archived-conversations")
    public ConversationArchiveService.ArchiveMutationResponse update(
        @Valid @RequestBody ArchiveUpdateRequest req,
        Authentication auth) {
        String userId = (String) auth.getPrincipal();
        ConversationArchiveService.ArchiveMutationResponse resp = archiveService.setArchived(
            userId, req.chatType(), req.peerId(), req.archived());
        log.info("conversation archive updated userId={} chatType={} peerId={} archived={}",
            userId, resp.chatType(), resp.peerId(), resp.archived());
        return resp;
    }

    /** 批量归档 / 取消归档（原子事务，最多 100 条）。 */
    @PutMapping("/me/archived-conversations/batch")
    public ConversationArchiveService.ArchiveBatchResponse updateBatch(
        @Valid @RequestBody ArchiveBatchRequest req,
        Authentication auth) {
        String userId = (String) auth.getPrincipal();
        List<ConversationArchiveService.ArchiveSettingItem> items = req.items().stream()
            .map(i -> new ConversationArchiveService.ArchiveSettingItem(
                i.chatType(), i.peerId(), i.archived()))
            .toList();
        ConversationArchiveService.ArchiveBatchResponse resp =
            archiveService.setArchivedBatch(userId, items);
        log.info("conversation archive batch updated userId={} count={}", userId, resp.count());
        return resp;
    }
}
