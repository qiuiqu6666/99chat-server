package com.chat99.server.push;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConversationFolderController {

    private static final Logger log = LoggerFactory.getLogger(ConversationFolderController.class);

    private final ConversationFolderService folderService;

    public ConversationFolderController(ConversationFolderService folderService) {
        this.folderService = folderService;
    }

    public record UpsertRequest(
        String folderId,
        @NotBlank String name,
        String scope,
        Integer sortOrder) {}

    public record MemberItemRequest(
        @NotBlank String chatType,
        @NotBlank String peerId,
        @NotNull Boolean inFolder) {}

    public record MembersRequest(@NotNull List<MemberItemRequest> items) {}

    public record MoveMemberRequest(
        @NotBlank String chatType,
        @NotBlank String peerId,
        @NotBlank String toFolderId) {}

    public record ReplaceMemberRequest(
        @NotBlank String chatType,
        @NotBlank String peerId) {}

    public record ReplaceFolderRequest(
        String folderId,
        @NotBlank String name,
        @NotBlank String scope,
        Integer sortOrder,
        List<ReplaceMemberRequest> members) {}

    public record ReplaceRequest(@NotNull List<ReplaceFolderRequest> folders) {}

    @GetMapping("/me/conversation-folders")
    public ConversationFolderService.FolderListResponse list(
        Authentication auth,
        @RequestParam(required = false) String scope) {
        return folderService.list((String) auth.getPrincipal(), scope);
    }

    @PutMapping("/me/conversation-folders")
    public ConversationFolderService.FolderView upsert(
        @Valid @RequestBody UpsertRequest req,
        Authentication auth) {
        String userId = (String) auth.getPrincipal();
        ConversationFolderService.FolderView view = folderService.upsert(
            userId,
            new ConversationFolderService.UpsertFolderRequest(
                req.folderId(), req.name(), req.scope(), req.sortOrder()));
        log.info("conversation folder upserted userId={} folderId={} scope={}",
            userId, view.folderId(), view.scope());
        return view;
    }

    @DeleteMapping("/me/conversation-folders/{folderId}")
    public Map<String, Object> delete(@PathVariable String folderId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        Map<String, Object> resp = folderService.delete(userId, folderId);
        log.info("conversation folder deleted userId={} folderId={}", userId, folderId);
        return resp;
    }

    @PutMapping("/me/conversation-folders/{folderId}/members")
    public ConversationFolderService.MembersMutationResponse setMembers(
        @PathVariable String folderId,
        @Valid @RequestBody MembersRequest req,
        Authentication auth) {
        String userId = (String) auth.getPrincipal();
        List<ConversationFolderService.MemberMutationItem> items = req.items().stream()
            .map(i -> new ConversationFolderService.MemberMutationItem(
                i.chatType(), i.peerId(), Boolean.TRUE.equals(i.inFolder())))
            .toList();
        ConversationFolderService.MembersMutationResponse resp =
            folderService.setMembers(userId, folderId, items);
        log.info("conversation folder members updated userId={} folderId={} count={}",
            userId, folderId, resp.count());
        return resp;
    }

    @PutMapping("/me/conversation-folders/move-member")
    public ConversationFolderService.MoveMemberResponse moveMember(
        @Valid @RequestBody MoveMemberRequest req,
        Authentication auth) {
        String userId = (String) auth.getPrincipal();
        ConversationFolderService.MoveMemberResponse resp = folderService.moveMember(
            userId,
            new ConversationFolderService.MoveMemberRequest(
                req.chatType(), req.peerId(), req.toFolderId()));
        log.info("conversation folder member moved userId={} folderId={} chatType={} peerId={}",
            userId, resp.folderId(), resp.chatType(), resp.peerId());
        return resp;
    }

    @PutMapping("/me/conversation-folders/replace")
    public ConversationFolderService.ReplaceResponse replace(
        @Valid @RequestBody ReplaceRequest req,
        Authentication auth) {
        String userId = (String) auth.getPrincipal();
        List<ConversationFolderService.ReplaceFolderItem> folders = req.folders().stream()
            .map(f -> new ConversationFolderService.ReplaceFolderItem(
                f.folderId(),
                f.name(),
                f.scope(),
                f.sortOrder(),
                f.members() == null
                    ? List.of()
                    : f.members().stream()
                        .map(m -> new ConversationFolderService.ReplaceMemberItem(
                            m.chatType(), m.peerId()))
                        .toList()))
            .toList();
        ConversationFolderService.ReplaceResponse resp = folderService.replaceAll(userId, folders);
        log.info("conversation folders replaced userId={} folderCount={}", userId, resp.folderCount());
        return resp;
    }
}
