package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import java.util.List;
import org.springframework.security.core.Authentication;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GroupMemberController {

    private final GroupMemberService memberService;
    private final GroupAccessService access;
    private final GroupProjectionService projection;
    private final ImUserIdService imUserIdService;
    private final MeGroupMembersChangesService membersChangesService;

    public GroupMemberController(GroupMemberService memberService,
                                 GroupAccessService access,
                                 GroupProjectionService projection,
                                 ImUserIdService imUserIdService,
                                 MeGroupMembersChangesService membersChangesService) {
        this.memberService = memberService;
        this.access = access;
        this.projection = projection;
        this.imUserIdService = imUserIdService;
        this.membersChangesService = membersChangesService;
    }

    /**
     * 群成员变动游标增量。{@code since_seq} 过旧返回 410 {@code CURSOR_EXPIRED}，
     * 客户端应回退 {@code GET /group/{groupId}/members} 本地快照（不再回源 IM）。
     */
    @GetMapping("/me/groups/{groupId}/members/changes")
    public MeGroupMembersChangesService.MembersChangesResponse listMemberChanges(
        @PathVariable String groupId,
        @RequestParam(name = "since_seq", defaultValue = "0") long sinceSeq,
        @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit,
        Authentication auth) {
        return membersChangesService.listChangesBySeq(
            groupId, (String) auth.getPrincipal(), sinceSeq, limit);
    }

    /**
     * v2 协议：群成员变更快照（revision 一致副本）。
     */
    @GetMapping("/me/groups/{groupId}/members/snapshot")
    public MeGroupMembersChangesService.SnapshotResponse snapshot(
            @PathVariable String groupId,
            Authentication auth,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Long snapshotRevision) {
        return membersChangesService.snapshot(
            groupId, (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit, snapshotRevision);
    }

    /**
     * v2 协议：群成员增量（opaqueCursor 防伪造）。
     */
    @GetMapping("/me/groups/{groupId}/members/changes/v2")
    public MeGroupMembersChangesService.ChangesResponse changesV2(
            @PathVariable String groupId,
            Authentication auth,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return membersChangesService.changes(
            groupId, (String) auth.getPrincipal(), cursor, limit == null ? 0 : limit);
    }

    /**
     * 群成员本地快照。{@code refresh} 已忽略（兼容旧客户端参数，不再触发 IM 拉取）。
     */
    @GetMapping("/group/{groupId}/members")
    public GroupMemberService.GroupMembersResponse listMembers(
        @PathVariable String groupId,
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "0") int offset,
        @RequestParam(defaultValue = "false") boolean refresh,
        @RequestParam(required = false) String role,
        Authentication auth) {
        return memberService.listMembers(
            groupId, (String) auth.getPrincipal(), limit, offset, refresh, role);
    }

    /** 查指定成员在本群的邀请人（任意群成员可读）。 */
    @GetMapping("/group/{groupId}/members/{userId}/inviter")
    public GroupMemberService.GroupMemberInviterResponse getInviter(
        @PathVariable String groupId,
        @PathVariable String userId,
        Authentication auth) {
        return memberService.getInviter(groupId, (String) auth.getPrincipal(), userId);
    }

    public record MuteStatusResponse(
        String userId,
        Long muteUntil,
        boolean isAllMuted) {}

    @GetMapping("/group/{groupId}/members/me/mute-status")
    public MuteStatusResponse getMyMuteStatus(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        GroupAccessService.validateGroupId(groupId);
        access.requireMember(groupId, userId);
        Long muteUntil = projection.activeMutedUntil(groupId, userId).orElse(null);
        boolean isAllMuted = projection.isShutUpAll(groupId);
        return new MuteStatusResponse(userId, muteUntil, isAllMuted);
    }

    public record MutedMembersResponse(
        List<ImAdminClient.MutedMemberInfo> members,
        boolean isAllMuted) {}

    @GetMapping("/group/{groupId}/members/muted")
    public MutedMembersResponse getMutedMembers(@PathVariable String groupId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        GroupAccessService.validateGroupId(groupId);
        access.requireMember(groupId, userId);
        List<ImAdminClient.MutedMemberInfo> members = projection.listActivelyMutedMembers(groupId).stream()
            .map(m -> new ImAdminClient.MutedMemberInfo(
                imUserIdService.toBusinessForDisplay(m.getUserId()),
                m.getMutedUntil(),
                m.getNameCard(),
                GroupRoleCodec.toImRole(m.getRole())))
            .toList();
        boolean isAllMuted = projection.isShutUpAll(groupId);
        return new MutedMembersResponse(members, isAllMuted);
    }
}
