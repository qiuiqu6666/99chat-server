package com.chat99.server.group;

import com.chat99.server.im.ImUserIdService;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupMemberService {

    private final GroupProjectionService projection;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessService access;
    private final GroupMemberEnrichmentService enrichment;
    private final ImUserIdService imUserIdService;

    public GroupMemberService(GroupProjectionService projection,
                              GroupMemberRepository memberRepository,
                              GroupAccessService access,
                              GroupMemberEnrichmentService enrichment,
                              ImUserIdService imUserIdService) {
        this.projection = projection;
        this.memberRepository = memberRepository;
        this.access = access;
        this.enrichment = enrichment;
        this.imUserIdService = imUserIdService;
    }

    /**
     * 本地投影快照；不再回源 IM（含 {@code refresh}）。
     * 增量请走 {@code GET /me/groups/{groupId}/members/changes}。
     */
    public GroupMembersResponse listMembers(String groupId, String callerUserId, int limit, int offset, boolean refresh, String role) {
        if (projection.isLocallyDismissed(groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        access.requireMember(groupId, callerUserId);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        int safeOffset = Math.max(offset, 0);

        GroupProfile profile = projection.findProfile(groupId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"));
        String roleFilter = parseRoleFilter(role);
        int total;
        List<GroupMember> rows;
        Pageable pageable = PageRequest.of(safeOffset / safeLimit, safeLimit);
        if (roleFilter == null) {
            total = (int) memberRepository.countActiveByGroupId(groupId);
            rows = memberRepository.findListByGroupId(groupId, pageable);
        } else if ("admins".equals(roleFilter)) {
            total = (int) memberRepository.countActiveByGroupIdAndRoleAtLeast(groupId, GroupRoleCodec.ADMIN);
            rows = memberRepository.findListByGroupIdAndRoleAtLeast(groupId, GroupRoleCodec.ADMIN, pageable);
        } else {
            total = (int) memberRepository.countActiveByGroupIdAndRoleBelow(groupId, GroupRoleCodec.ADMIN);
            rows = memberRepository.findListByGroupIdAndRoleBelow(groupId, GroupRoleCodec.ADMIN, pageable);
        }
        // R1-hide：库内可能是 IM 号，响应与 enrichment 一律业务号
        List<String> storedIds = rows.stream().map(GroupMember::getUserId).toList();
        Map<String, String> bizByStored = imUserIdService.toBusinessForDisplayBatch(storedIds);
        List<String> businessUserIds = storedIds.stream()
            .map(id -> bizByStored.getOrDefault(id, id))
            .distinct()
            .toList();
        Map<String, String> imByBiz = resolveImByBusiness(storedIds, bizByStored, businessUserIds);
        Map<String, GroupMemberEnrichmentService.UserBrief> briefs = enrichment.loadUserBriefs(businessUserIds);
        Map<String, String> remarks = enrichment.loadFriendRemarks(callerUserId, businessUserIds);

        List<String> inviterStored = rows.stream()
            .map(GroupMember::getInvitedBy)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        Map<String, String> inviterBizByStored = inviterStored.isEmpty()
            ? Map.of()
            : imUserIdService.toBusinessForDisplayBatch(inviterStored);
        List<String> inviterBizIds = inviterStored.stream()
            .map(id -> inviterBizByStored.getOrDefault(id, id))
            .distinct()
            .toList();
        Map<String, GroupMemberEnrichmentService.UserBrief> inviterBriefs = inviterBizIds.isEmpty()
            ? Map.of()
            : enrichment.loadUserBriefs(inviterBizIds);

        List<GroupMemberView> items = rows.stream()
            .map(member -> toView(
                member, callerUserId, bizByStored, imByBiz, briefs, remarks,
                inviterBizByStored, inviterBriefs))
            .toList();
        return new GroupMembersResponse(groupId, items, total, safeLimit, safeOffset);
    }

    /**
     * 查指定成员在本群的邀请人。调用者须已在群内；目标不在群内为 404。
     */
    public GroupMemberInviterResponse getInviter(String groupId, String callerUserId, String targetUserId) {
        if (projection.isLocallyDismissed(groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        access.requireMember(groupId, callerUserId);
        if (projection.findProfile(groupId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND");
        }
        String uid = targetUserId.trim();
        GroupMember member = findActiveMember(groupId, uid);
        if (member == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER");
        }
        String businessUserId = imUserIdService.toBusinessForDisplay(member.getUserId());
        String invitedByUserId = null;
        String invitedByNickname = null;
        if (member.getInvitedBy() != null && !member.getInvitedBy().isBlank()) {
            invitedByUserId = imUserIdService.toBusinessForDisplay(member.getInvitedBy());
            Map<String, GroupMemberEnrichmentService.UserBrief> inviterBriefs =
                enrichment.loadUserBriefs(List.of(invitedByUserId));
            GroupMemberEnrichmentService.UserBrief inviterBrief = inviterBriefs.get(invitedByUserId);
            invitedByNickname = (inviterBrief == null
                || inviterBrief.nickname() == null
                || inviterBrief.nickname().isBlank())
                ? invitedByUserId
                : inviterBrief.nickname();
        }
        return new GroupMemberInviterResponse(
            groupId,
            businessUserId,
            invitedByUserId,
            invitedByNickname,
            member.getJoinChannel());
    }

    private GroupMember findActiveMember(String groupId, String targetUserId) {
        GroupMember member = memberRepository.findByGroupIdAndUserIdActive(groupId, targetUserId).orElse(null);
        if (member != null) {
            return member;
        }
        String businessUserId = imUserIdService.toBusinessForDisplay(targetUserId);
        if (!businessUserId.equals(targetUserId)) {
            member = memberRepository.findByGroupIdAndUserIdActive(groupId, businessUserId).orElse(null);
            if (member != null) {
                return member;
            }
        }
        String imId = imUserIdService.findImUserId(businessUserId).orElse(null);
        if (imId != null && !imId.isBlank() && !imId.equals(targetUserId)) {
            return memberRepository.findByGroupIdAndUserIdActive(groupId, imId).orElse(null);
        }
        return null;
    }

    private static String parseRoleFilter(String role) {
        if (role == null) {
            return null;
        }
        String trimmed = role.trim();
        if ("admins".equals(trimmed) || "members".equals(trimmed)) {
            return trimmed;
        }
        return null;
    }

    public record GroupMembersResponse(
        String groupId,
        List<GroupMemberView> items,
        int total,
        int limit,
        int offset) {}

    public record GroupMemberInviterResponse(
        String groupId,
        String userId,
        String invitedByUserId,
        String invitedByNickname,
        String joinChannel) {}

    private Map<String, String> resolveImByBusiness(
        List<String> storedIds,
        Map<String, String> bizByStored,
        List<String> businessUserIds) {
        Map<String, String> imByBiz = new java.util.HashMap<>();
        for (String stored : storedIds) {
            if (stored == null || stored.isBlank()) {
                continue;
            }
            String id = stored.trim();
            String biz = bizByStored.getOrDefault(id, id);
            if (!biz.equals(id)) {
                // 库内存的是 IM 号
                imByBiz.putIfAbsent(biz, id);
            }
        }
        for (String biz : businessUserIds) {
            if (biz == null || biz.isBlank() || imByBiz.containsKey(biz)) {
                continue;
            }
            imUserIdService.findImUserId(biz).ifPresent(im -> imByBiz.put(biz, im));
        }
        return imByBiz;
    }

    private static GroupMemberView toView(
        GroupMember member,
        String callerUserId,
        Map<String, String> bizByStored,
        Map<String, String> imByBiz,
        Map<String, GroupMemberEnrichmentService.UserBrief> briefs,
        Map<String, String> remarks,
        Map<String, String> inviterBizByStored,
        Map<String, GroupMemberEnrichmentService.UserBrief> inviterBriefs) {
        String businessUserId = bizByStored.getOrDefault(member.getUserId(), member.getUserId());
        GroupMemberEnrichmentService.UserBrief brief = briefs.get(businessUserId);
        String nickname = (brief == null || brief.nickname() == null || brief.nickname().isBlank())
            ? businessUserId
            : brief.nickname();
        String avatarUrl = brief == null ? null : brief.avatarUrl();
        Long joinedAt = member.getJoinedAt() == null ? null : member.getJoinedAt().toEpochMilli();
        String invitedByUserId = null;
        String invitedByNickname = null;
        if (member.getInvitedBy() != null && !member.getInvitedBy().isBlank()) {
            invitedByUserId = inviterBizByStored.getOrDefault(member.getInvitedBy(), member.getInvitedBy());
            GroupMemberEnrichmentService.UserBrief inviterBrief = inviterBriefs.get(invitedByUserId);
            invitedByNickname = (inviterBrief == null
                || inviterBrief.nickname() == null
                || inviterBrief.nickname().isBlank())
                ? invitedByUserId
                : inviterBrief.nickname();
        }
        return new GroupMemberView(
            businessUserId,
            imByBiz.get(businessUserId),
            nickname,
            avatarUrl,
            remarks.get(businessUserId),
            member.getNameCard(),
            member.getRole(),
            GroupRoleCodec.roleName(member.getRole()),
            joinedAt,
            businessUserId.equals(callerUserId),
            invitedByUserId,
            invitedByNickname,
            member.getJoinChannel());
    }
}
