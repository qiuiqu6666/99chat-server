package com.chat99.server.group;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeGroupsController {

    private final GroupProfileService profileService;
    private final MeGroupsChangesService changesService;

    public MeGroupsController(GroupProfileService profileService,
                              MeGroupsChangesService changesService) {
        this.profileService = profileService;
        this.changesService = changesService;
    }

    /**
     * 我的群列表（本地投影）。
     * <ul>
     *   <li>默认 {@code limit=100}（上限 200），请分页，勿一次拉全量</li>
     *   <li>{@code refresh=true} 仅用于建群失败恢复等场景，会异步入队同步并跳过短缓存</li>
     *   <li>群通知审批请用 {@code GET /me/join-applications}，勿对本接口结果扇出
     *       {@code GET /group/{id}/join-applications}</li>
     * </ul>
     */
    @GetMapping("/me/groups")
    public GroupProfileService.MyGroupsResponse listMyGroups(
        @RequestParam(defaultValue = "100") int limit,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "false") boolean refresh,
        Authentication auth) {
        return profileService.listMyGroups((String) auth.getPrincipal(), limit, offset, refresh);
    }

    /**
     * v2 协议：群展示变更快照（revision 一致副本）。
     */
    @GetMapping("/me/groups/snapshot")
    public MeGroupsChangesService.SnapshotResponse snapshot(
            Authentication auth,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Long snapshotRevision) {
        return changesService.snapshot(
            (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit, snapshotRevision);
    }
}
