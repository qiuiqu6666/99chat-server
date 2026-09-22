package com.chat99.server.group;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeGroupsChangesController {

    private final MeGroupsChangesService changesService;

    public MeGroupsChangesController(MeGroupsChangesService changesService) {
        this.changesService = changesService;
    }

    /**
     * 群 Entity 展示字段增量（名/头像/公告）。
     * {@code since_seq} 过旧返回 410 {@code CURSOR_EXPIRED}，客户端应回退 {@code GET /me/groups} 快照。
     */
    /**
     * v2 协议：群展示变更增量（按 opaqueCursor）。
     */
    @GetMapping("/me/groups/changes/v2")
    public MeGroupsChangesService.ChangesResponse changesV2(
            Authentication auth,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return changesService.changes(
            (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit);
    }

    @GetMapping("/me/groups/changes")
    public MeGroupsChangesService.GroupsChangesResponse listChanges(
        @RequestParam(name = "since_seq", defaultValue = "0") long sinceSeq,
        @RequestParam(defaultValue = "100") int limit,
        Authentication auth) {
        return changesService.listChangesBySeq((String) auth.getPrincipal(), sinceSeq, limit);
    }
}
