package com.chat99.server.group;

import com.chat99.server.im.ImUserIdService;
import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class GroupChangedPublisher {

    private final ImUserIdService imUserIdService;
    private final GroupRealtimePublisher groupRealtime;
    private final GroupProjectionService projection;
    private final GroupChangeEmitter changeEmitter;
    private final GroupFanoutTargetResolver fanoutTargets;

    public GroupChangedPublisher(ImUserIdService imUserIdService,
                                 GroupRealtimePublisher groupRealtime,
                                 GroupProjectionService projection,
                                 GroupChangeEmitter changeEmitter,
                                 GroupFanoutTargetResolver fanoutTargets) {
        this.imUserIdService = imUserIdService;
        this.groupRealtime = groupRealtime;
        this.projection = projection;
        this.changeEmitter = changeEmitter;
        this.fanoutTargets = fanoutTargets;
    }

    public int publishToAll(String groupId, String action, String operatorUserId,
                             List<String> memberUserIds, Map<String, Object> detail) {
        List<String> targets = toBusinessIds(fanoutTargets.resolveMemberTargets(groupId));
        publish(groupId, action, operatorUserId, memberUserIds, targets, detail);
        return targets.size();
    }

    @Deprecated
    public void publishMemberLeftOrRemoved(String groupId, String action, String operatorUserId,
                                           List<String> removedUserIds) {
        List<String> removed = toBusinessIds(normalize(removedUserIds));
        if (removed.isEmpty()) {
            return;
        }
        List<String> targets = toBusinessIds(fanoutTargets.resolveMemberTargetsUnion(groupId, removed));
        changeEmitter.emitMemberLeftOrRemoved(
            groupId, action, toBusinessId(operatorUserId), removed, targets, Instant.now(), GroupChangeEventSource.IM_CALLBACK);
    }

    @Deprecated
    public void publishMemberAdded(String groupId, String operatorUserId, List<String> addedUserIds) {
        List<String> added = toBusinessIds(normalize(addedUserIds));
        if (added.isEmpty()) {
            return;
        }
        List<String> targets = toBusinessIds(fanoutTargets.resolveMemberTargetsUnion(groupId, added));
        changeEmitter.emitMemberAdded(
            groupId, toBusinessId(operatorUserId), added, targets, Instant.now(), GroupChangeEventSource.IM_CALLBACK);
    }

    public void publishOwnerChanged(String groupId, String operatorUserId,
                                    String newOwnerUserId, String oldOwnerUserId) {
        publishToAll(groupId, GroupRealtimePublisher.ACTION_OWNER_CHANGED, operatorUserId, List.of(),
            GroupRealtimeDetailFactory.ownerChanged(
                toBusinessId(newOwnerUserId), toBusinessId(oldOwnerUserId), profileUpdatedAt(groupId)));
    }

    @Deprecated
    public void publishGroupDismissed(String groupId, String operatorUserId, List<String> memberUserIds) {
        changeEmitter.emitGroupDismissed(
            groupId, toBusinessId(operatorUserId), toBusinessIds(normalize(memberUserIds)), Instant.now(), GroupChangeEventSource.IM_CALLBACK);
    }

    private Instant profileUpdatedAt(String groupId) {
        return projection.findProfile(groupId)
            .map(GroupProfile::getUpdatedAt)
            .orElse(Instant.now());
    }

    private void publish(String groupId, String action, String operatorUserId,
                         List<String> memberUserIds, List<String> targetUserIds,
                         Map<String, Object> detail) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return;
        }
        Instant occurredAt = profileUpdatedAt(groupId);
        long occurredAtMs = occurredAt.toEpochMilli();
        Map<String, Object> enriched = GroupRealtimeDetailFactory.enrichWithOccurredAt(detail, occurredAtMs);
        groupRealtime.publish(
            groupId,
            action,
            toBusinessId(operatorUserId),
            toBusinessIds(memberUserIds),
            toBusinessIds(targetUserIds),
            enriched,
            GroupChangeIdGenerator.newChangeEventId(),
            occurredAtMs,
            GroupTimelineRank.forAction(action));
    }

    private String toBusinessId(String imOrAccount) {
        if (imOrAccount == null || imOrAccount.isBlank()) {
            return imOrAccount;
        }
        return imUserIdService.toBusinessForDisplay(imOrAccount.trim());
    }

    private List<String> toBusinessIds(List<String> imOrAccounts) {
        if (imOrAccounts == null || imOrAccounts.isEmpty()) {
            return List.of();
        }
        Map<String, String> mapped = imUserIdService.toBusinessForDisplayBatch(imOrAccounts);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : imOrAccounts) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim();
            out.add(mapped.getOrDefault(id, id));
        }
        return new ArrayList<>(out);
    }

    private static List<String> normalize(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String userId : userIds) {
            if (userId != null && !userId.isBlank()) {
                out.add(userId.trim());
            }
        }
        return out.stream().distinct().toList();
    }
}
