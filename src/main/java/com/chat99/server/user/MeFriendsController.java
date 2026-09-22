package com.chat99.server.user;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeFriendsController {

    private final UserFriendService friendService;
    private final MeFriendsChangesService changesService;

    public MeFriendsController(UserFriendService friendService, MeFriendsChangesService changesService) {
        this.friendService = friendService;
        this.changesService = changesService;
    }

    /**
     * 旧协议（保留）：{@code GET /me/friends} 拉好友列表。
     * 客户端应改用 {@code /me/friends/snapshot}。
     */
    @GetMapping("/me/friends")
    public UserFriendService.FriendListResponse list(Authentication auth,
                                                     @RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) String cursor) {
        return friendService.listFriends((String) auth.getPrincipal(), limit, cursor);
    }

    /**
     * v2 协议：好友通讯录快照。当前 {@code snapshotRevision} 范围内的一致副本。
     * <p>{@code snapshotRevision} 可选：不传则用当前最新 revision；传则按该版本返回。
     * 返回 {@link MeFriendsChangesService.SnapshotResponse}：{@code snapshotRevision + opaqueCursor + hasMore + total + items[]}。
     */
    @GetMapping("/me/friends/snapshot")
    public MeFriendsChangesService.SnapshotResponse snapshot(
            Authentication auth,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Long snapshotRevision) {
        return changesService.snapshot(
            (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit, snapshotRevision);
    }

    /**
     * 好友通讯录 Difference：返回 {@code seq > since_seq} 的事件。
     * 游标过期 → HTTP 410 {@code SNAPSHOT_REQUIRED}，回退 {@link #list}。
     * @deprecated 旧协议，保留向后兼容；新客户端应改用 {@code changes(opaqueCursor)}。
     */
    @Deprecated
    @GetMapping("/me/friends/changes")
    public MeFriendsChangesService.FriendsChangesResponse changes(
        Authentication auth,
        @RequestParam(name = "since_seq", defaultValue = "0") long sinceSeq,
        @RequestParam(required = false) Integer limit) {
        return changesService.listChangesBySeq(
            (String) auth.getPrincipal(),
            sinceSeq,
            limit == null ? 0 : limit);
    }

    /**
     * v2 协议：好友通讯录增量。{@code sinceCursor} 由上次 snapshot/changes 响应返回。
     * <p>响应 {@link MeFriendsChangesService.ChangesResponse}：
     * {@code snapshotRevision + toRevision + opaqueCursor + hasMore + serverTime + events[]}。
     */
    @GetMapping("/me/friends/changes/v2")
    public MeFriendsChangesService.ChangesResponse changesV2(
            Authentication auth,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return changesService.changes(
            (String) auth.getPrincipal(),
            cursor,
            limit == null ? 0 : limit);
    }

    @GetMapping("/me/friends/{peerUserId}/relation")
    public UserFriendService.FriendRelationResponse relation(Authentication auth,
                                                             @PathVariable String peerUserId) {
        return friendService.getFriendRelation((String) auth.getPrincipal(), peerUserId);
    }

    public record RemarkPayload(String remark) {}

    @PutMapping("/me/friends/{friendUserId}/remark")
    public UserFriendService.RemarkUpdateResponse updateRemark(Authentication auth,
                                                               @PathVariable String friendUserId,
                                                               @Valid @RequestBody RemarkPayload body) {
        return friendService.updateRemark((String) auth.getPrincipal(), friendUserId, body.remark());
    }

    @DeleteMapping("/me/friends/{friendUserId}")
    public UserFriendService.DeleteFriendResponse delete(Authentication auth,
                                                         @PathVariable String friendUserId) {
        return friendService.deleteFriendMutual((String) auth.getPrincipal(), friendUserId);
    }
}
