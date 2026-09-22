package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.im.ImRestException;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(GroupGovernanceService.class);

    public record LeaveGroupResponse(String groupId, String status, int memberCount) {}

    public record DismissGroupResponse(String groupId, String status) {}

    public record RemoveMemberResponse(String groupId, String userId, String status, int memberCount) {}

    public record RemoveMemberResultItem(String userId, String status, String code) {}

    public record BatchRemoveMembersResponse(String groupId, List<RemoveMemberResultItem> results, int memberCount) {}

    public record TransferOwnerResponse(String groupId, String ownerUserId, String oldOwnerUserId) {}

    public record MemberRoleRequest(int role) {}

    public record MemberRoleResultItem(String userId, String status, String code, GroupMemberView member) {}

    public record BatchMemberRoleResponse(String groupId, int role, List<MemberRoleResultItem> results) {}

    public record MemberMuteRequest(long muteSeconds) {}

    public record MuteAllRequest(boolean shutUpAllMember) {}

    static final int MAX_BATCH_MEMBER_ROLES = 30;

    private final GroupAccessService access;
    private final ImAdminClient im;
    private final ImUserIdService imUserIdService;
    private final GroupProjectionService projection;
    private final GroupMemberRepository memberRepository;
    private final GroupMemberEnrichmentService enrichment;
    private final UserOwnedGroupService ownedGroupService;
    private final GroupChangedPublisher changedPublisher;
    private final GroupSystemNoticeService systemNoticeService;
    private final GroupChangeEmitter groupChangeEmitter;
    private final GroupFanoutTargetResolver fanoutTargets;
    private final Executor roleBatchExecutor;
    private final GroupImSyncService groupImSyncService;

    public GroupGovernanceService(GroupAccessService access,
                                  ImAdminClient im,
                              ImUserIdService imUserIdService,
                                  GroupProjectionService projection,
                                  GroupMemberRepository memberRepository,
                                  GroupMemberEnrichmentService enrichment,
                                  UserOwnedGroupService ownedGroupService,
                                  GroupChangedPublisher changedPublisher,
                                  GroupSystemNoticeService systemNoticeService,
                                  GroupChangeEmitter groupChangeEmitter,
                                  GroupFanoutTargetResolver fanoutTargets,
                                  @Qualifier(GroupMemberRoleBatchExecutorConfig.BEAN_NAME)
                                  Executor roleBatchExecutor,
                                  GroupImSyncService groupImSyncService) {
        this.access = access;
        this.im = im;
        this.imUserIdService = imUserIdService;
        this.projection = projection;
        this.memberRepository = memberRepository;
        this.enrichment = enrichment;
        this.ownedGroupService = ownedGroupService;
        this.changedPublisher = changedPublisher;
        this.systemNoticeService = systemNoticeService;
        this.groupChangeEmitter = groupChangeEmitter;
        this.fanoutTargets = fanoutTargets;
        this.roleBatchExecutor = roleBatchExecutor;
        this.groupImSyncService = groupImSyncService;
    }

    @Transactional
    public LeaveGroupResponse leaveGroup(String groupId, String userId) {
        GroupAccessService.validateGroupId(groupId);
        access.requireGroupInfo(groupId);
        String role = access.requireMemberRole(groupId, userId);
        if (GroupRoleCodec.isOwnerImRole(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OWNER_CANNOT_LEAVE");
        }
        List<String> targets = unionTargets(groupId, List.of(userId));
        projection.onMembersRemoved(groupId, List.of(userId));
        groupChangeEmitter.emitMemberLeftOrRemoved(
            groupId,
            GroupRealtimePublisher.ACTION_MEMBER_LEFT,
            userId,
            List.of(userId),
            targets,
            Instant.now(),
            GroupChangeEventSource.REST_LEAVE);
        groupImSyncService.syncDeleteMembers(groupId, List.of(userId));
        int memberCount = memberCount(groupId);
        return new LeaveGroupResponse(groupId, "left", memberCount);
    }

    @Transactional
    public DismissGroupResponse dismissGroup(String groupId, String userId) {
        GroupAccessService.validateGroupId(groupId);
        access.requireGroupInfo(groupId);
        access.requireOwnerRole(groupId, userId);
        LinkedHashSet<String> targetSet = new LinkedHashSet<>(fanoutTargets.resolveMemberTargets(groupId));
        if (userId != null && !userId.isBlank()) {
            targetSet.add(userId.trim());
        }
        List<String> targets = List.copyOf(targetSet);
        projection.onGroupDismissed(groupId);
        ownedGroupService.recordDestroyed(groupId);
        groupChangeEmitter.emitGroupDismissed(
            groupId, userId, targets, Instant.now(), GroupChangeEventSource.REST_DISMISS);
        groupImSyncService.syncDestroyGroup(groupId);
        return new DismissGroupResponse(groupId, "dismissed");
    }

    @Transactional
    public RemoveMemberResponse kickMember(String groupId, String operatorUserId, String targetUserId) {
        BatchRemoveMembersResponse batch = kickMembers(groupId, operatorUserId, List.of(targetUserId));
        RemoveMemberResultItem item = batch.results().get(0);
        if ("failed".equals(item.status())) {
            throw new ResponseStatusException(statusForKickFailure(item.code()), item.code());
        }
        return new RemoveMemberResponse(groupId, targetUserId, "removed", batch.memberCount());
    }

    @Transactional
    public BatchRemoveMembersResponse kickMembers(String groupId, String operatorUserId, List<String> userIds) {
        GroupAccessService.validateGroupId(groupId);
        access.requireGroupInfo(groupId);
        List<String> normalized = normalizeUserIds(userIds);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (normalized.size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }
        String operatorRole = access.requireAdminRole(groupId, operatorUserId);

        Map<String, RemoveMemberResultItem> resultByUser = new LinkedHashMap<>();
        List<String> toRemove = new ArrayList<>();
        for (String targetUserId : normalized) {
            RemoveMemberResultItem failed = precheckKickTarget(groupId, operatorUserId, operatorRole, targetUserId);
            if (failed != null) {
                resultByUser.put(targetUserId, failed);
                continue;
            }
            toRemove.add(targetUserId);
        }
        if (!toRemove.isEmpty()) {
            projection.onMembersRemoved(groupId, toRemove);
            List<String> targets = unionTargets(groupId, toRemove);
            groupChangeEmitter.emitMemberLeftOrRemoved(
                groupId,
                GroupRealtimePublisher.ACTION_MEMBER_REMOVED,
                operatorUserId,
                toRemove,
                targets,
                Instant.now(),
                GroupChangeEventSource.REST_KICK);
            groupImSyncService.syncDeleteMembers(groupId, toRemove);
            for (String userId : toRemove) {
                resultByUser.put(userId, new RemoveMemberResultItem(userId, "removed", null));
            }
        }
        List<RemoveMemberResultItem> results = normalized.stream().map(resultByUser::get).toList();
        return new BatchRemoveMembersResponse(groupId, results, memberCount(groupId));
    }

    private RemoveMemberResultItem precheckKickTarget(String groupId, String operatorUserId,
                                                      String operatorRole, String targetUserId) {
        if (operatorUserId.equals(targetUserId)) {
            return kickFailed(targetUserId, "INVALID_INPUT");
        }
        String targetRole = projection.findMember(groupId, targetUserId)
            .map(m -> GroupRoleCodec.toImRole(m.getRole()))
            .orElse(null);
        if (targetRole == null) {
            return kickFailed(targetUserId, "NOT_GROUP_MEMBER");
        }
        try {
            validateKickPermission(operatorRole, targetRole);
        } catch (ResponseStatusException e) {
            return kickFailed(targetUserId, e.getReason());
        }
        return null;
    }

    private static RemoveMemberResultItem kickFailed(String userId, String code) {
        return new RemoveMemberResultItem(userId, "failed", code);
    }

    private static List<String> normalizeUserIds(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (userId == null) {
                continue;
            }
            String trimmed = userId.trim();
            if (!trimmed.isEmpty()) {
                seen.add(trimmed);
            }
        }
        return List.copyOf(seen);
    }

    private static HttpStatus statusForKickFailure(String code) {
        if (code == null) {
            return HttpStatus.BAD_GATEWAY;
        }
        return switch (code) {
            case "INVALID_INPUT" -> HttpStatus.BAD_REQUEST;
            case "NOT_GROUP_MEMBER", "CANNOT_KICK_OWNER", "CANNOT_KICK_ADMIN" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.FORBIDDEN;
        };
    }

    @Transactional
    public GroupMemberView updateMemberRole(String groupId, String operatorUserId,
                                            String targetUserId, int role) {
        GroupAccessService.validateGroupId(groupId);
        access.requireGroupInfo(groupId);
        access.requireOwnerRole(groupId, operatorUserId);
        if (role != GroupRoleCodec.ADMIN && role != GroupRoleCodec.MEMBER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (targetUserId == null || targetUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        MemberRoleResultItem item = applyOneMemberRole(
            groupId, operatorUserId, targetUserId.trim(), role);
        if ("failed".equals(item.status())) {
            throw new ResponseStatusException(statusForRoleFailure(item.code()), item.code());
        }
        return item.member();
    }

    /**
     * 批量设置成员角色：同步只校验群主与入参并立即返回 {@code accepted}；
     * IM 改角色 / 本地投影 / 通知在后台异步执行，成功后发 TCP {@code member_role_changed}。
     */
    public BatchMemberRoleResponse updateMemberRoles(String groupId, String operatorUserId,
                                                     int role, List<String> userIds) {
        GroupAccessService.validateGroupId(groupId);
        access.requireGroupInfo(groupId);
        access.requireOwnerRole(groupId, operatorUserId);
        if (role != GroupRoleCodec.ADMIN && role != GroupRoleCodec.MEMBER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        List<String> normalized = normalizeUserIds(userIds);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (normalized.size() > MAX_BATCH_MEMBER_ROLES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE");
        }

        List<MemberRoleResultItem> results = normalized.stream()
            .map(userId -> new MemberRoleResultItem(userId, "accepted", null, null))
            .toList();
        List<String> asyncTargets = List.copyOf(normalized);
        roleBatchExecutor.execute(() -> processMemberRolesAsync(groupId, operatorUserId, role, asyncTargets));
        log.info("group member roles batch accepted groupId={} operator={} role={} count={}",
            groupId, operatorUserId, role, asyncTargets.size());
        return new BatchMemberRoleResponse(groupId, role, results);
    }

    private void processMemberRolesAsync(String groupId, String operatorUserId,
                                         int role, List<String> userIds) {
        int updated = 0;
        int failed = 0;
        for (String targetUserId : userIds) {
            try {
                MemberRoleResultItem item = applyOneMemberRole(groupId, operatorUserId, targetUserId, role);
                if ("failed".equals(item.status())) {
                    failed++;
                    log.warn("group member role async failed groupId={} target={} code={}",
                        groupId, targetUserId, item.code());
                } else {
                    updated++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("group member role async error groupId={} target={} err={}",
                    groupId, targetUserId, e.getMessage());
            }
        }
        log.info("group member roles batch async done groupId={} operator={} role={} updated={} failed={}",
            groupId, operatorUserId, role, updated, failed);
    }

    private MemberRoleResultItem applyOneMemberRole(String groupId, String operatorUserId,
                                                    String targetUserId, int role) {
        String targetRole;
        try {
            targetRole = access.requireMemberRole(groupId, targetUserId);
        } catch (ResponseStatusException e) {
            return roleFailed(targetUserId, e.getReason() != null ? e.getReason() : "NOT_GROUP_MEMBER");
        }
        if (GroupRoleCodec.isOwnerImRole(targetRole)) {
            return roleFailed(targetUserId, "CANNOT_CHANGE_OWNER_ROLE");
        }
        int previousRole = GroupRoleCodec.fromImRoleOrDefault(targetRole);
        if (previousRole != role) {
            try {
                im.modifyMemberImRole(groupId, targetUserId, GroupRoleCodec.toImRole(role));
            } catch (ImRestException e) {
                log.warn("group member role im failed groupId={} target={} err={}",
                    groupId, targetUserId, e.getMessage());
                return roleFailed(targetUserId, "IM_REST_ERROR");
            }
            projection.onMemberRoleChanged(groupId, targetUserId, GroupRoleCodec.toImRole(role));
            final int prev = previousRole;
            final int next = role;
            runAfterCommit(() -> {
                try {
                    changedPublisher.publishToAll(
                        groupId,
                        GroupRealtimePublisher.ACTION_MEMBER_ROLE_CHANGED,
                        operatorUserId,
                        List.of(targetUserId),
                        GroupRealtimeDetailFactory.memberRoleChanged(
                            targetUserId, next, prev, operatorUserId, Instant.now()));
                } catch (Exception e) {
                    log.warn("group member role publish failed groupId={} target={}", groupId, targetUserId, e);
                }
            });
            if (role == GroupRoleCodec.ADMIN && previousRole != GroupRoleCodec.ADMIN) {
                systemNoticeService.recordAndPublish(
                    groupId, GroupSystemNoticeType.grant_administrator, operatorUserId, targetUserId);
            } else if (role == GroupRoleCodec.MEMBER && previousRole == GroupRoleCodec.ADMIN) {
                systemNoticeService.recordAndPublish(
                    groupId, GroupSystemNoticeType.revoke_administrator, operatorUserId, targetUserId);
            }
            log.info("group member role updated groupId={} operator={} target={} role={}",
                groupId, operatorUserId, targetUserId, role);
        }
        try {
            return new MemberRoleResultItem(
                targetUserId, "updated", null, toMemberView(groupId, targetUserId, operatorUserId));
        } catch (ResponseStatusException e) {
            return roleFailed(targetUserId, e.getReason() != null ? e.getReason() : "NOT_GROUP_MEMBER");
        }
    }

    private static MemberRoleResultItem roleFailed(String userId, String code) {
        return new MemberRoleResultItem(userId, "failed", code, null);
    }

    private static HttpStatus statusForRoleFailure(String code) {
        if (code == null) {
            return HttpStatus.BAD_GATEWAY;
        }
        return switch (code) {
            case "INVALID_INPUT" -> HttpStatus.BAD_REQUEST;
            case "NOT_GROUP_MEMBER", "CANNOT_CHANGE_OWNER_ROLE", "NOT_GROUP_OWNER" -> HttpStatus.FORBIDDEN;
            case "IM_REST_ERROR", "IM_NOT_CONFIGURED" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.FORBIDDEN;
        };
    }

    @Transactional
    public TransferOwnerResponse transferOwner(String groupId, String operatorUserId, String newOwnerUserId) {
        GroupAccessService.validateGroupId(groupId);
        access.requireGroupInfo(groupId);
        access.requireOwnerRole(groupId, operatorUserId);
        if (newOwnerUserId == null || newOwnerUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String trimmed = newOwnerUserId.trim();
        if (operatorUserId.equals(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        access.requireMemberRole(groupId, trimmed);
        try {
            im.changeGroupOwner(groupId, imUserIdService.toIm(trimmed), imUserIdService.toIm(operatorUserId));
        } catch (ImRestException e) {
            throw mapImError(e);
        }
        projection.onOwnerChanged(groupId, trimmed, operatorUserId);
        runAfterCommit(() -> {
            try {
                changedPublisher.publishOwnerChanged(groupId, operatorUserId, trimmed, operatorUserId);
            } catch (Exception e) {
                log.warn("transfer owner publish failed groupId={}", groupId, e);
            }
        });
        systemNoticeService.recordAndPublish(
            groupId, GroupSystemNoticeType.transfer_owner, operatorUserId, trimmed);
        return new TransferOwnerResponse(groupId, trimmed, operatorUserId);
    }

    @Transactional
    public void muteMember(String groupId, String operatorUserId, String targetUserId, long muteSeconds) {
        long t0 = System.nanoTime();
        GroupAccessService.validateGroupId(groupId);
        access.requireGroupInfo(groupId);
        access.requireAdminRole(groupId, operatorUserId);
        access.requireMemberRole(groupId, targetUserId);
        if (muteSeconds < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        long tIm = System.nanoTime();
        try {
            im.modifyMemberMute(groupId, imUserIdService.toIm(targetUserId), muteSeconds);
        } catch (ImRestException e) {
            throw mapImError(e);
        }
        long imWriteMs = elapsedMs(tIm);
        long mutedUntil = muteSeconds == 0 ? 0L : Instant.now().getEpochSecond() + muteSeconds;
        long tProj = System.nanoTime();
        projection.onMemberMutedUntilChanged(groupId, imUserIdService.toIm(targetUserId), mutedUntil);
        long projectionMs = elapsedMs(tProj);
        String action = muteSeconds == 0
            ? GroupRealtimePublisher.ACTION_MEMBER_UNMUTED
            : GroupRealtimePublisher.ACTION_MEMBER_MUTED;
        runAfterCommit(() -> {
            long tPub = System.nanoTime();
            try {
                changedPublisher.publishToAll(
                    groupId,
                    action,
                    operatorUserId,
                    List.of(targetUserId),
                    java.util.Map.of(
                        "memberUserId", targetUserId,
                        "mutedUntil", String.valueOf(mutedUntil)));
            } catch (Exception e) {
                log.warn("mute member publish failed groupId={} target={}", groupId, targetUserId, e);
            }
            log.info(
                "group_mute_member timing groupId={} target={} imWriteMs={} projectionMs={} publishScheduleMs={} totalMs={}",
                groupId, targetUserId, imWriteMs, projectionMs, elapsedMs(tPub), elapsedMs(t0));
        });
    }

    @Transactional
    public void muteAll(String groupId, String operatorUserId, boolean shutUpAllMember) {
        long t0 = System.nanoTime();
        GroupAccessService.validateGroupId(groupId);
        access.requireGroupInfo(groupId);
        access.requireAdminRole(groupId, operatorUserId);
        // 软全员禁言：业务态写投影；IM 原生 ShutUpAllMember 必须保持 Off，
        // 否则普通成员发 group_tip 会被 IM 层直接拦截，BeforeSend 回调无法放行。
        long tIm = System.nanoTime();
        try {
            im.modifyGroupMuteAll(groupId, false);
        } catch (ImRestException e) {
            throw mapImError(e);
        }
        long imWriteMs = elapsedMs(tIm);
        long tProj = System.nanoTime();
        projection.onShutUpAllChanged(groupId, shutUpAllMember);
        long projectionMs = elapsedMs(tProj);
        runAfterCommit(() -> {
            long tPub = System.nanoTime();
            int targetCount = 0;
            try {
                targetCount = changedPublisher.publishToAll(
                    groupId,
                    GroupRealtimePublisher.ACTION_GROUP_MUTE_ALL_CHANGED,
                    operatorUserId,
                    List.of(),
                    java.util.Map.of("shutUpAllMember", shutUpAllMember ? "On" : "Off"));
            } catch (Exception e) {
                log.warn("mute-all publish failed groupId={}", groupId, e);
            }
            log.info(
                "group_mute_all timing groupId={} imWriteMs={} projectionMs={} publishScheduleMs={} totalMs={} targetCount={}",
                groupId, imWriteMs, projectionMs, elapsedMs(tPub), elapsedMs(t0), targetCount);
        });
    }

    private static void validateKickPermission(String operatorRole, String targetRole) {
        if (GroupRoleCodec.isOwnerImRole(targetRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_KICK_OWNER");
        }
        if (GroupRoleCodec.isAdminImRole(operatorRole) && GroupRoleCodec.isAdminImRole(targetRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CANNOT_KICK_ADMIN");
        }
    }

    private int memberCount(String groupId) {
        return projection.findProfile(groupId)
            .map(GroupProfile::getMemberCount)
            .orElseGet(() -> im.countGroupMembers(groupId));
    }

    private List<String> unionTargets(String groupId, List<String> affectedUserIds) {
        return fanoutTargets.resolveMemberTargetsUnion(groupId, affectedUserIds);
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private GroupMemberView toMemberView(String groupId, String targetUserId, String callerUserId) {
        String businessUserId = imUserIdService.toBusinessForDisplay(targetUserId);
        GroupMember member = projection.findMember(groupId, targetUserId).orElse(null);
        if (member == null) {
            String imId = imUserIdService.findImUserId(businessUserId).orElse(null);
            if (imId != null && !imId.equals(targetUserId)) {
                member = projection.findMember(groupId, imId).orElse(null);
            }
        }
        if (member == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOT_GROUP_MEMBER");
        }
        var briefs = enrichment.loadUserBriefs(List.of(businessUserId));
        var remarks = enrichment.loadFriendRemarks(callerUserId, List.of(businessUserId));
        var brief = briefs.get(businessUserId);
        String nickname = (brief == null || brief.nickname() == null || brief.nickname().isBlank())
            ? businessUserId
            : brief.nickname();
        String avatarUrl = brief == null ? null : brief.avatarUrl();
        Long joinedAt = member.getJoinedAt() == null ? null : member.getJoinedAt().toEpochMilli();
        String imUserId = imUserIdService.findImUserId(businessUserId).orElse(null);
        String invitedByUserId = null;
        String invitedByNickname = null;
        if (member.getInvitedBy() != null && !member.getInvitedBy().isBlank()) {
            invitedByUserId = imUserIdService.toBusinessForDisplay(member.getInvitedBy());
            var inviterBriefs = enrichment.loadUserBriefs(List.of(invitedByUserId));
            var inviterBrief = inviterBriefs.get(invitedByUserId);
            invitedByNickname = (inviterBrief == null
                || inviterBrief.nickname() == null
                || inviterBrief.nickname().isBlank())
                ? invitedByUserId
                : inviterBrief.nickname();
        }
        return new GroupMemberView(
            businessUserId,
            imUserId,
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

    private static ResponseStatusException mapImError(ImRestException e) {
        if ("IM_NOT_CONFIGURED".equals(e.getMessage())) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IM_NOT_CONFIGURED");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "IM_REST_ERROR");
    }
}
