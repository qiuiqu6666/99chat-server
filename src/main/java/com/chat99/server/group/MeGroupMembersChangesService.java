package com.chat99.server.group;

import com.chat99.server.sync.OpaqueCursor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 群成员通讯录 Difference 接口（v2 协议）。
 * <ul>
 *   <li>{@code snapshot(groupId, opaqueCursor, limit, snapshotRevision)} — 当前快照一致副本</li>
 *   <li>{@code changes(groupId, opaqueCursor, limit)} — 增量（opaqueCursor 防伪造）</li>
 * </ul>
 * 群成员按 groupId 隔离（每个群各自 revision）。
 */
@Service
public class MeGroupMembersChangesService {

    private static final Logger log = LoggerFactory.getLogger(MeGroupMembersChangesService.class);
    public static final String DOMAIN_PREFIX = "groupMembers:";   // 群成员域 opaqueCursor 的 domain 前缀

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    /** 统一视图：snapshot item 与 changes event 共用。 */
    public record MemberChangeEvent(
        String id,                   // userId
        String eventId,              // 仅 events 有；snapshot 项为空
        String operation,            // "upsert" | "delete"，snapshot 为空
        long seq,                    // 保留旧字段（向后兼容）
        long itemVersion,
        boolean deleted,             // tombstone
        String type,
        String groupId,
        String userId,
        String nickName,
        String avatarUrl,
        Integer role,
        int memberCount,
        long updatedAt) {}

    public record SnapshotResponse(
        long snapshotRevision,
        long currentRevision,
        String opaqueCursor,
        boolean hasMore,
        int memberCount,
        List<MemberChangeEvent> items) {}

    public record ChangesResponse(
        long snapshotRevision,
        long toRevision,
        String opaqueCursor,
        boolean hasMore,
        int memberCount,
        long serverTime,
        List<MemberChangeEvent> events) {}

    @Deprecated
    public record MembersChangesResponse(
        long nextSeq,
        boolean hasMore,
        int memberCount,
        List<MemberChangeEvent> events) {}

    private final GroupMemberChangeRepository changeRepository;
    private final GroupAccessService access;
    private final GroupProjectionService projection;
    private final ObjectMapper json;

    public MeGroupMembersChangesService(GroupMemberChangeRepository changeRepository,
                                        GroupAccessService access,
                                        GroupProjectionService projection,
                                        ObjectMapper json) {
        this.changeRepository = changeRepository;
        this.access = access;
        this.projection = projection;
        this.json = json;
    }

    public SnapshotResponse snapshot(String groupId, String userId, String opaqueCursor, int limit, Long snapshotRevision) {
        GroupAccessService.validateGroupId(groupId);
        access.requireMember(groupId, userId);
        int safeLimit = clamp(limit);
        long rev;
        if (snapshotRevision != null && snapshotRevision > 0) {
            OpaqueCursor.validate(OpaqueCursor.decode(opaqueCursor), DOMAIN_PREFIX + groupId);
            rev = snapshotRevision;
        } else {
            Long max = changeRepository.findMaxRevisionForGroup(groupId);
            rev = max == null ? 0L : max;
        }
        List<GroupMemberChange> rows = changeRepository.findForGroupAtRevision(
            groupId, rev, PageRequest.of(0, safeLimit));
        List<MemberChangeEvent> items = rows.stream().map(this::toItem).toList();
        Long maxRev = changeRepository.findMaxRevisionForGroup(groupId);
        long currentRev = maxRev == null ? 0L : maxRev;
        String nextOpaque = OpaqueCursor.encode(DOMAIN_PREFIX + groupId, currentRev, currentRev);
        boolean hasMore = items.size() == safeLimit && currentRev > rev;
        int memberCount = currentMemberCount(groupId);
        return new SnapshotResponse(currentRev, currentRev, nextOpaque, hasMore, memberCount, items);
    }

    public ChangesResponse changes(String groupId, String userId, String opaqueCursor, int limit) {
        GroupAccessService.validateGroupId(groupId);
        access.requireMember(groupId, userId);
        if (opaqueCursor == null || opaqueCursor.isBlank()) {
            opaqueCursor = OpaqueCursor.empty(DOMAIN_PREFIX + groupId);
        }
        OpaqueCursor.CursorPayload payload;
        try {
            payload = OpaqueCursor.decode(opaqueCursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.GONE, "INVALID_CURSOR");
        }
        OpaqueCursor.validate(payload, DOMAIN_PREFIX + groupId);

        int safeLimit = clamp(limit);
        long sinceRevision = payload.revision();

        Long minRev = changeRepository.findMinRevisionForGroup(groupId);
        if (minRev != null && sinceRevision > 0 && sinceRevision < minRev) {
            throw new ResponseStatusException(HttpStatus.GONE, "SNAPSHOT_REQUIRED");
        }

        List<GroupMemberChange> fetched = changeRepository.findForGroupSinceRevision(
            groupId, sinceRevision, PageRequest.of(0, safeLimit + 1));
        boolean hasMore = fetched.size() > safeLimit;
        List<GroupMemberChange> rows = hasMore ? fetched.subList(0, safeLimit) : fetched;
        List<MemberChangeEvent> events = rows.stream().map(this::toItem).toList();
        Long maxRev = changeRepository.findMaxRevisionForGroup(groupId);
        long currentRev = maxRev == null ? sinceRevision : maxRev;
        long toRevision = rows.isEmpty() ? currentRev : rows.get(rows.size() - 1).getRevision();
        String nextOpaque = OpaqueCursor.encode(DOMAIN_PREFIX + groupId, toRevision, toRevision);
        long serverTime = System.currentTimeMillis();
        int memberCount = currentMemberCount(groupId);
        return new ChangesResponse(currentRev, toRevision, nextOpaque, hasMore, memberCount, serverTime, events);
    }

    @Deprecated
    public MembersChangesResponse listChangesBySeq(String groupId, String userId, long sinceSeq, int limit) {
        GroupAccessService.validateGroupId(groupId);
        access.requireMember(groupId, userId);

        int safeLimit = clamp(limit);
        long safeSince = Math.max(sinceSeq, 0L);

        Long minSeq = changeRepository.findMinSeqForGroup(groupId);
        if (safeSince > 0 && minSeq != null && safeSince < minSeq) {
            throw new ResponseStatusException(HttpStatus.GONE, "CURSOR_EXPIRED");
        }

        List<GroupMemberChange> rows = changeRepository.findForGroupSinceSeq(
            groupId, safeSince, PageRequest.of(0, safeLimit));
        List<MemberChangeEvent> events = rows.stream().map(this::toItem).toList();
        long nextSeq;
        if (events.isEmpty()) {
            Long max = changeRepository.findMaxSeqForGroup(groupId);
            nextSeq = max == null ? safeSince : Math.max(safeSince, max);
        } else {
            nextSeq = events.get(events.size() - 1).seq();
        }
        boolean hasMore = events.size() == safeLimit;
        int memberCount = currentMemberCount(groupId);
        return new MembersChangesResponse(nextSeq, hasMore, memberCount, events);
    }

    public long currentSyncSeq(String groupId) {
        GroupAccessService.validateGroupId(groupId);
        Long max = changeRepository.findMaxSeqForGroup(groupId);
        return max == null ? 0L : max;
    }

    public long currentRevision(String groupId) {
        GroupAccessService.validateGroupId(groupId);
        Long max = changeRepository.findMaxRevisionForGroup(groupId);
        return max == null ? 0L : max;
    }

    private MemberChangeEvent toItem(GroupMemberChange row) {
        Map<String, Object> payload = readPayload(row.getPayloadJson());
        String op = switch (row.getEventType()) {
            case GroupMemberChangeWriter.TYPE_MEMBER_UPSERTED -> "upsert";
            case GroupMemberChangeWriter.TYPE_MEMBER_REMOVED -> "delete";
            default -> "upsert";
        };
        return new MemberChangeEvent(
            row.getUserId(),
            row.getEventId(),
            op,
            row.getSeq(),
            row.getItemVersion(),
            row.isDeleted(),
            row.getEventType(),
            row.getGroupId(),
            row.getUserId(),
            str(payload.get("nickName")),
            str(payload.get("avatarUrl")),
            intOrNull(payload.get("role")),
            row.getMemberCount(),
            row.getCreatedAt());
    }

    private Map<String, Object> readPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = json.readValue(payloadJson, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.debug("group member change payload parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private int currentMemberCount(String groupId) {
        return projection.findProfile(groupId)
            .map(GroupProfile::getMemberCount)
            .orElse(0);
    }

    private static int clamp(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) return n.intValue();
        if (v == null) return null;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
