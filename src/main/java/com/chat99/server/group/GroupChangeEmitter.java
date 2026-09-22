package com.chat99.server.group;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.realtime.GroupRealtimePublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GroupChangeEmitter {

    private static final Logger log = LoggerFactory.getLogger(GroupChangeEmitter.class);
    static final long DUPLICATE_WINDOW_MS = 120_000L;

    private final ImAdminClient im;
    private final GroupRealtimePublisher groupRealtime;
    private final GroupProjectionService projection;
    private final GroupAvatarDefaults avatarDefaults;
    private final GroupChangeEventRepository changeEventRepository;
    private final GroupChangeEventMapper changeEventMapper;
    private final GroupDisplaySeqAllocator seqAllocator;
    private final GroupMemberChangeWriter memberChangeWriter;
    private final GroupMemberEnrichmentService enrichment;
    private final GroupFanoutTargetResolver fanoutTargets;
    private final GroupChangeRevisionSeqAllocator revisionAllocator;
    private final ObjectMapper json;

    public GroupChangeEmitter(ImAdminClient im,
                              GroupRealtimePublisher groupRealtime,
                              GroupProjectionService projection,
                              GroupAvatarDefaults avatarDefaults,
                              GroupChangeEventRepository changeEventRepository,
                              GroupChangeEventMapper changeEventMapper,
                              GroupDisplaySeqAllocator seqAllocator,
                              GroupMemberChangeWriter memberChangeWriter,
                              GroupMemberEnrichmentService enrichment,
                              GroupFanoutTargetResolver fanoutTargets,
                              GroupChangeRevisionSeqAllocator revisionAllocator,
                              ObjectMapper json) {
        this.im = im;
        this.groupRealtime = groupRealtime;
        this.projection = projection;
        this.avatarDefaults = avatarDefaults;
        this.changeEventRepository = changeEventRepository;
        this.changeEventMapper = changeEventMapper;
        this.seqAllocator = seqAllocator;
        this.memberChangeWriter = memberChangeWriter;
        this.enrichment = enrichment;
        this.fanoutTargets = fanoutTargets;
        this.revisionAllocator = revisionAllocator;
        this.json = json;
    }

    public void emitGroupChanged(String groupId,
                                 String action,
                                 String operatorUserId,
                                 List<String> memberUserIds,
                                 List<String> targetUserIds,
                                 Map<String, Object> detail,
                                 Instant occurredAt,
                                 String source) {
        List<String> targets = normalizeIds(targetUserIds);
        if (groupId == null || groupId.isBlank() || action == null || action.isBlank() || targets.isEmpty()) {
            return;
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        String changeEventId = GroupChangeIdGenerator.newChangeEventId();
        long occurredAtMs = occurredAt.toEpochMilli();
        Integer timelineRank = GroupTimelineRank.forAction(action);
        long groupSeq = seqAllocator.nextSeq();
        Map<String, Object> enriched = GroupRealtimeDetailFactory.enrichWithOccurredAt(detail, occurredAtMs);
        enriched.put("groupSeq", groupSeq);
        List<String> members = normalizeIds(memberUserIds);
        persistAudit(changeEventId, groupId, action, operatorUserId, members, occurredAtMs,
            timelineRank, source, enriched, groupSeq);
        groupRealtime.publish(groupId, action, operatorUserId, members, targets, enriched,
            changeEventId, occurredAtMs, timelineRank);
        log.debug("group change emitted groupId={} action={} changeEventId={} groupSeq={} targets={}",
            groupId, action, changeEventId, groupSeq, targets.size());
    }

    /**
     * 群名 / 头像 / 公告等展示字段变更：落库 + TCP，detail 带齐快照与 groupSeq。
     */
    public void emitDisplayInfoChanged(String groupId,
                                       String action,
                                       String operatorUserId,
                                       Instant occurredAt,
                                       String source) {
        if (groupId == null || groupId.isBlank() || action == null || action.isBlank()) {
            return;
        }
        GroupProfile profile = projection.findProfile(groupId).orElse(null);
        Instant at = occurredAt != null ? occurredAt
            : (profile != null && profile.getUpdatedAt() != null ? profile.getUpdatedAt() : Instant.now());
        Map<String, Object> detail = GroupRealtimeDetailFactory.groupDisplaySnapshot(profile, at);
        List<String> targets = fanoutTargets.resolveMemberTargets(groupId);
        emitGroupChanged(groupId, action, operatorUserId, List.of(), targets, detail, at, source);
    }

    public void emitMemberAdded(String groupId,
                                String operatorUserId,
                                List<String> addedUserIds,
                                List<String> allTargetUserIds,
                                Instant occurredAt,
                                String source) {
        List<String> added = normalizeIds(addedUserIds);
        if (added.isEmpty()) {
            return;
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        String changeEventId = GroupChangeIdGenerator.newChangeEventId();
        long occurredAtMs = occurredAt.toEpochMilli();
        Integer timelineRank = GroupTimelineRank.forAction(GroupRealtimePublisher.ACTION_MEMBER_ADDED);

        int memberCount = memberCount(groupId);
        List<String> targets = normalizeIds(allTargetUserIds);
        if (targets.isEmpty()) {
            return;
        }
        Set<String> addedSet = new LinkedHashSet<>(added);
        List<String> existing = targets.stream().filter(id -> !addedSet.contains(id)).toList();

        long memberSeq = memberChangeWriter.writeUpsertedBatch(
            groupId, added, memberCount, buildUpsertPayloads(groupId, added));

        Map<String, Object> auditDetail = GroupRealtimeDetailFactory.enrichWithOccurredAt(
            GroupRealtimeDetailFactory.memberAddedForExisting(memberCount, added, occurredAt),
            occurredAtMs);
        long groupSeq = seqAllocator.nextSeq();
        auditDetail.put("groupSeq", groupSeq);
        if (memberSeq > 0) {
            auditDetail.put("seq", memberSeq);
        }
        persistAudit(changeEventId, groupId, GroupRealtimePublisher.ACTION_MEMBER_ADDED, operatorUserId,
            added, occurredAtMs, timelineRank, source, auditDetail, groupSeq);

        if (!existing.isEmpty()) {
            groupRealtime.publish(
                groupId,
                GroupRealtimePublisher.ACTION_MEMBER_ADDED,
                operatorUserId,
                added,
                existing,
                new LinkedHashMap<>(auditDetail),
                changeEventId,
                occurredAtMs,
                timelineRank);
        }
        for (String userId : added) {
            Map<String, Object> joinerDetail = GroupRealtimeDetailFactory.enrichWithOccurredAt(
                buildJoinerDetail(groupId, userId, memberCount, added, occurredAt),
                occurredAtMs);
            joinerDetail.put("groupSeq", groupSeq);
            if (memberSeq > 0) {
                joinerDetail.put("seq", memberSeq);
            }
            groupRealtime.publish(
                groupId,
                GroupRealtimePublisher.ACTION_MEMBER_ADDED,
                operatorUserId,
                added,
                List.of(userId),
                joinerDetail,
                changeEventId,
                occurredAtMs,
                timelineRank);
        }
    }

    public void emitMemberLeftOrRemoved(String groupId,
                                        String action,
                                        String operatorUserId,
                                        List<String> removedUserIds,
                                        List<String> targetUserIds,
                                        Instant occurredAt,
                                        String source) {
        List<String> removed = normalizeIds(removedUserIds);
        if (removed.isEmpty()) {
            return;
        }
        int memberCount = memberCount(groupId);
        long memberSeq = memberChangeWriter.writeRemovedBatch(groupId, removed, memberCount);
        Map<String, Object> detail = GroupRealtimeDetailFactory.memberCountChanged(
            memberCount, removed, occurredAt == null ? Instant.now() : occurredAt);
        if (memberSeq > 0) {
            detail.put("seq", memberSeq);
        }
        emitGroupChanged(groupId, action, operatorUserId, removed, targetUserIds, detail, occurredAt, source);
    }

    public void emitGroupDismissed(String groupId,
                                   String operatorUserId,
                                   List<String> memberUserIds,
                                   Instant occurredAt,
                                   String source) {
        if (groupId == null || groupId.isBlank()) {
            return;
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        List<String> targets = normalizeIds(memberUserIds);
        if (targets.isEmpty() && operatorUserId != null && !operatorUserId.isBlank()) {
            targets = List.of(operatorUserId.trim());
        }
        String changeEventId = GroupChangeIdGenerator.newChangeEventId();
        long occurredAtMs = occurredAt.toEpochMilli();
        Integer timelineRank = GroupTimelineRank.forAction(GroupRealtimePublisher.ACTION_GROUP_DISMISSED);
        long groupSeq = seqAllocator.nextSeq();
        Map<String, Object> enriched = GroupRealtimeDetailFactory.enrichWithOccurredAt(Map.of(), occurredAtMs);
        enriched.put("groupSeq", groupSeq);
        persistAudit(
            changeEventId,
            groupId,
            GroupRealtimePublisher.ACTION_GROUP_DISMISSED,
            operatorUserId,
            List.of(),
            occurredAtMs,
            timelineRank,
            source,
            enriched,
            groupSeq);
        if (targets.isEmpty()) {
            log.warn("group_dismissed audit only; empty targets groupId={}", groupId.trim());
            return;
        }
        groupRealtime.publish(
            groupId,
            GroupRealtimePublisher.ACTION_GROUP_DISMISSED,
            operatorUserId,
            List.of(),
            targets,
            enriched,
            changeEventId,
            occurredAtMs,
            timelineRank);
        log.debug("group dismiss emitted groupId={} changeEventId={} targets={}",
            groupId, changeEventId, targets.size());
    }

    /** IM 回调去重：REST 已落库且 TCP 同步过时，跳过重复 emit。 */
    public boolean hasRecentDuplicate(String groupId, String action, List<String> memberUserIds) {
        if (groupId == null || groupId.isBlank() || action == null || action.isBlank()) {
            return false;
        }
        long minOccurredAt = System.currentTimeMillis() - DUPLICATE_WINDOW_MS;
        List<GroupChangeEvent> recent = changeEventRepository.findRecentByGroupAndAction(
            groupId.trim(),
            action,
            minOccurredAt,
            org.springframework.data.domain.PageRequest.of(0, 8));
        if (recent.isEmpty()) {
            return false;
        }
        if (GroupRealtimePublisher.ACTION_GROUP_DISMISSED.equals(action)) {
            return true;
        }
        Set<String> want = new LinkedHashSet<>(normalizeIds(memberUserIds));
        for (GroupChangeEvent row : recent) {
            if (want.equals(changeEventMapper.readMemberUserIdSet(row.getMemberUserIdsJson()))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Map<String, Object>> buildUpsertPayloads(String groupId, List<String> userIds) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, GroupMemberEnrichmentService.UserBrief> briefs = enrichment.loadUserBriefs(userIds);
        for (String userId : userIds) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            GroupMember member = projection.findMember(groupId, userId).orElse(null);
            if (member != null) {
                payload.put("role", member.getRole());
            }
            GroupMemberEnrichmentService.UserBrief brief = briefs.get(userId);
            if (brief != null) {
                payload.put("nickName", brief.nickname());
                payload.put("avatarUrl", brief.avatarUrl());
            }
            out.put(userId, payload);
        }
        return out;
    }

    private Map<String, Object> buildJoinerDetail(String groupId, String userId, int memberCount,
                                                  List<String> added, Instant occurredAt) {
        GroupProfile profile = projection.findProfile(groupId).orElse(null);
        GroupMember member = projection.findMember(groupId, userId).orElse(null);
        if (profile == null || member == null) {
            return GroupRealtimeDetailFactory.memberAddedForExisting(memberCount, added, occurredAt);
        }
        Map<String, Object> detail = GroupRealtimeDetailFactory.memberAddedForJoiner(
            profile, member, memberCount, added, occurredAt);
        detail.put("avatarUrl", avatarDefaults.resolve(profile.getAvatarUrl()));
        return detail;
    }

    private int memberCount(String groupId) {
        return projection.findProfile(groupId)
            .map(GroupProfile::getMemberCount)
            .orElseGet(() -> im.countGroupMembers(groupId));
    }

    private void persistAudit(String changeEventId,
                              String groupId,
                              String action,
                              String operatorUserId,
                              List<String> memberUserIds,
                              long occurredAtMs,
                              Integer timelineRank,
                              String source,
                              Map<String, Object> detail,
                              long groupSeq) {
        if (changeEventId == null || changeEventId.isBlank()) {
            return;
        }
        if (changeEventRepository.existsById(changeEventId)) {
            return;
        }
        GroupChangeEvent row = new GroupChangeEvent();
        row.setChangeEventId(changeEventId);
        row.setGroupId(groupId.trim());
        row.setAction(action);
        row.setOperatorUserId(blankToNull(operatorUserId));
        row.setMemberUserIdsJson(writeJson(memberUserIds));
        row.setOccurredAt(occurredAtMs);
        row.setTimelineRank(timelineRank);
        row.setSource(source == null ? GroupChangeEventSource.IM_CALLBACK : source);
        row.setDetailJson(writeJson(detail));
        row.setGroupSeq(groupSeq);
        long revision = revisionAllocator.next();
        row.setRevision(revision);
        row.setItemVersion(revision);
        changeEventRepository.save(row);
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("group change event json serialize failed: {}", e.getMessage());
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static List<String> normalizeIds(List<String> userIds) {
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
