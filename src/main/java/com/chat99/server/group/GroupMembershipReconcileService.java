package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 以当前 IM 角色为准，把本地有效群成员裁齐；不写 IM、不解散群。
 */
@Service
public class GroupMembershipReconcileService {

    private static final Logger log = LoggerFactory.getLogger(GroupMembershipReconcileService.class);

    public record Result(
        String groupId,
        int localAliveBefore,
        int imRolesChecked,
        int removedLocal,
        int addedLocal,
        int imMemberNum,
        int localAliveAfter,
        List<String> removedUserIds) {}

    private final GroupProfileRepository profileRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupProjectionService projection;
    private final GroupChangeEmitter changeEmitter;
    private final GroupFanoutTargetResolver fanoutTargets;
    private final ImAdminClient im;

    public GroupMembershipReconcileService(GroupProfileRepository profileRepository,
                                           GroupMemberRepository memberRepository,
                                           GroupProjectionService projection,
                                           GroupChangeEmitter changeEmitter,
                                           GroupFanoutTargetResolver fanoutTargets,
                                           ImAdminClient im) {
        this.profileRepository = profileRepository;
        this.memberRepository = memberRepository;
        this.projection = projection;
        this.changeEmitter = changeEmitter;
        this.fanoutTargets = fanoutTargets;
        this.im = im;
    }

    @Transactional
    public Result reconcile(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String gid = groupId.trim();
        GroupProfile profile = profileRepository.findById(gid).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND"));
        if (profile.isDismissed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "GROUP_DISMISSED");
        }
        List<String> localAlive = memberRepository.findActiveUserIdsByGroupId(gid);
        int before = localAlive.size();
        Map<String, String> roles = im.getRolesInGroup(gid, localAlive);
        List<String> firstPass = new ArrayList<>();
        for (String userId : localAlive) {
            if (!inGroup(roles.get(userId))) {
                firstPass.add(userId);
            }
        }
        Map<String, String> confirmed = firstPass.isEmpty()
            ? Map.of()
            : im.getRolesInGroup(gid, firstPass);
        List<String> toRemove = new ArrayList<>();
        for (String userId : firstPass) {
            if (!inGroup(confirmed.get(userId))) {
                toRemove.add(userId);
            }
        }
        if (!toRemove.isEmpty()) {
            projection.onMembersRemoved(gid, toRemove);
            List<String> targets = fanoutTargets.resolveMemberTargetsUnion(gid, toRemove);
            changeEmitter.emitMemberLeftOrRemoved(
                gid,
                GroupRealtimePublisher.ACTION_MEMBER_LEFT,
                null,
                toRemove,
                targets,
                Instant.now(),
                GroupChangeEventSource.RECONCILE);
            log.info("group membership reconcile removed groupId={} count={}", gid, toRemove.size());
        }
        int added = addMissingFromIm(gid);
        int imMemberNum = Math.max(im.countGroupMembers(gid), 0);
        int after = (int) memberRepository.countActiveByGroupId(gid);
        return new Result(gid, before, roles.size(), toRemove.size(), added, imMemberNum, after, toRemove);
    }

    @Transactional
    public Result reconcileUsers(String groupId, List<String> userIds) {
        if (groupId == null || groupId.isBlank() || userIds == null || userIds.isEmpty()) {
            return new Result(groupId == null ? "" : groupId.trim(), 0, 0, 0, 0, 0, 0, List.of());
        }
        String gid = groupId.trim();
        GroupProfile profile = profileRepository.findById(gid).orElse(null);
        if (profile == null || profile.isDismissed()) {
            return new Result(gid, 0, 0, 0, 0, 0, 0, List.of());
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String userId : userIds) {
            if (userId != null && !userId.isBlank()) {
                ids.add(userId.trim());
            }
        }
        if (ids.isEmpty()) {
            return new Result(gid, 0, 0, 0, 0, 0, 0, List.of());
        }
        List<String> targets = List.copyOf(ids);
        Map<String, String> roles = im.getRolesInGroup(gid, targets);
        List<String> firstPass = new ArrayList<>();
        List<String> inGroup = new ArrayList<>();
        for (String userId : targets) {
            if (inGroup(roles.get(userId))) {
                inGroup.add(userId);
            } else {
                firstPass.add(userId);
            }
        }
        Map<String, String> confirmed = firstPass.isEmpty()
            ? Map.of()
            : im.getRolesInGroup(gid, firstPass);
        List<String> toRemove = new ArrayList<>();
        for (String userId : firstPass) {
            if (!inGroup(confirmed.get(userId))
                && memberRepository.findByGroupIdAndUserIdActive(gid, userId).isPresent()) {
                toRemove.add(userId);
            }
            if (inGroup(confirmed.get(userId))) {
                inGroup.add(userId);
            }
        }
        if (!toRemove.isEmpty()) {
            projection.onMembersRemoved(gid, toRemove);
            changeEmitter.emitMemberLeftOrRemoved(
                gid,
                GroupRealtimePublisher.ACTION_MEMBER_LEFT,
                null,
                toRemove,
                fanoutTargets.resolveMemberTargetsUnion(gid, toRemove),
                Instant.now(),
                GroupChangeEventSource.RECONCILE);
        }
        List<String> toAdd = new ArrayList<>();
        for (String userId : inGroup) {
            if (memberRepository.findByGroupIdAndUserIdActive(gid, userId).isEmpty()) {
                toAdd.add(userId);
            }
        }
        if (!toAdd.isEmpty()) {
            projection.onMembersJoined(gid, toAdd, null, null);
            changeEmitter.emitMemberAdded(
                gid,
                null,
                toAdd,
                fanoutTargets.resolveMemberTargetsUnion(gid, toAdd),
                Instant.now(),
                GroupChangeEventSource.RECONCILE);
        }
        int after = (int) memberRepository.countActiveByGroupId(gid);
        log.info("group membership reconcile users groupId={} checked={} removed={} added={}",
            gid, targets.size(), toRemove.size(), toAdd.size());
        return new Result(gid, targets.size(), roles.size(), toRemove.size(), toAdd.size(), 0, after, toRemove);
    }

    private int addMissingFromIm(String groupId) {
        int imMemberNum = im.countGroupMembers(groupId);
        if (imMemberNum <= 0) {
            return 0;
        }
        List<String> imMembers = im.listGroupMemberUserIds(groupId, 0);
        if (imMembers.size() < Math.max(1, (int) Math.floor(imMemberNum * 0.9d))) {
            log.warn("group membership reconcile skip add; IM roster incomplete groupId={} fetched={} memberNum={}",
                groupId, imMembers.size(), imMemberNum);
            return 0;
        }
        LinkedHashSet<String> local = new LinkedHashSet<>(memberRepository.findActiveUserIdsByGroupId(groupId));
        List<String> toAdd = new ArrayList<>();
        for (String userId : imMembers) {
            if (userId != null && !userId.isBlank() && !local.contains(userId.trim())) {
                toAdd.add(userId.trim());
            }
        }
        if (toAdd.isEmpty()) {
            return 0;
        }
        projection.onMembersJoined(groupId, toAdd, null, null);
        List<String> targets = fanoutTargets.resolveMemberTargetsUnion(groupId, toAdd);
        changeEmitter.emitMemberAdded(
            groupId,
            null,
            toAdd,
            targets,
            Instant.now(),
            GroupChangeEventSource.RECONCILE);
        log.info("group membership reconcile added groupId={} count={}", groupId, toAdd.size());
        return toAdd.size();
    }

    private static boolean inGroup(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT).replace("_", "");
        return !normalized.equals("notmember") && !normalized.equals("none");
    }
}
