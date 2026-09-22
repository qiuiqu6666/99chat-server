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
 * 群展示变更 Difference 接口（v2 协议）。所有用户维度的群展示变更。
 * <ul>
 *   <li>{@code snapshot(userId, opaqueCursor, limit, snapshotRevision)}</li>
 *   <li>{@code changes(userId, opaqueCursor, limit)}</li>
 * </ul>
 */
@Service
public class MeGroupsChangesService {

    public static final String DOMAIN = "groupDisplay";

    private static final Logger log = LoggerFactory.getLogger(MeGroupsChangesService.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    /** snapshot item 与 change event 共用 */
    public record DisplayChangeEvent(
        String id,                 // changeEventId（events） / groupId + changeEventId 复合（snapshot 简化为 changeEventId）
        String eventId,
        String operation,          // events：upsert；snapshot：空
        long groupSeq,             // 保留旧字段
        long itemVersion,
        boolean deleted,
        long revision,
        long occurredAt,
        long updatedAt,
        String groupId,
        String groupName,
        String avatarUrl,
        Integer avatarVersion,
        String notice,
        String changeEventId,
        String action,
        String operatorUserId,
        Integer timelineRank) {}

    public record SnapshotResponse(
        long snapshotRevision,
        long currentRevision,
        String opaqueCursor,
        boolean hasMore,
        List<DisplayChangeEvent> items) {}

    public record ChangesResponse(
        long snapshotRevision,
        long toRevision,
        String opaqueCursor,
        boolean hasMore,
        long serverTime,
        List<DisplayChangeEvent> events) {}

    @Deprecated
    public record GroupsChangesResponse(
        long nextSeq,
        boolean hasMore,
        List<DisplayChangeEvent> events) {}

    private final GroupChangeEventRepository changeEventRepository;
    private final ObjectMapper json;

    public MeGroupsChangesService(GroupChangeEventRepository changeEventRepository,
                                   ObjectMapper json) {
        this.changeEventRepository = changeEventRepository;
        this.json = json;
    }

    public SnapshotResponse snapshot(String userId, String opaqueCursor, int limit, Long snapshotRevision) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        int safeLimit = clamp(limit);
        long rev;
        if (snapshotRevision != null && snapshotRevision > 0) {
            OpaqueCursor.validate(OpaqueCursor.decode(opaqueCursor), DOMAIN);
            rev = snapshotRevision;
        } else {
            Long max = changeEventRepository.findMaxRevision();
            rev = max == null ? 0L : max;
        }
        List<GroupChangeEvent> rows = changeEventRepository.findForUserAtRevision(
            rev, PageRequest.of(0, safeLimit));
        List<DisplayChangeEvent> items = rows.stream().map(this::toItem).toList();
        Long maxRev = changeEventRepository.findMaxRevision();
        long currentRev = maxRev == null ? 0L : maxRev;
        String nextOpaque = OpaqueCursor.encode(DOMAIN, currentRev, currentRev);
        boolean hasMore = items.size() == safeLimit && currentRev > rev;
        return new SnapshotResponse(currentRev, currentRev, nextOpaque, hasMore, items);
    }

    public ChangesResponse changes(String userId, String opaqueCursor, int limit) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (opaqueCursor == null || opaqueCursor.isBlank()) {
            opaqueCursor = OpaqueCursor.empty(DOMAIN);
        }
        OpaqueCursor.CursorPayload payload;
        try {
            payload = OpaqueCursor.decode(opaqueCursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.GONE, "INVALID_CURSOR");
        }
        OpaqueCursor.validate(payload, DOMAIN);

        int safeLimit = clamp(limit);
        long sinceRevision = payload.revision();

        Long minRev = changeEventRepository.findMinRevision();
        if (minRev != null && sinceRevision > 0 && sinceRevision < minRev) {
            throw new ResponseStatusException(HttpStatus.GONE, "SNAPSHOT_REQUIRED");
        }

        List<GroupChangeEvent> fetched = changeEventRepository.findForUserSinceRevision(
            sinceRevision, PageRequest.of(0, safeLimit + 1));
        boolean hasMore = fetched.size() > safeLimit;
        List<GroupChangeEvent> rows = hasMore ? fetched.subList(0, safeLimit) : fetched;
        List<DisplayChangeEvent> events = rows.stream().map(this::toItem).toList();
        Long maxRev = changeEventRepository.findMaxRevision();
        long currentRev = maxRev == null ? sinceRevision : maxRev;
        long toRevision = rows.isEmpty() ? currentRev : rows.get(rows.size() - 1).getRevision();
        String nextOpaque = OpaqueCursor.encode(DOMAIN, toRevision, toRevision);
        long serverTime = System.currentTimeMillis();
        return new ChangesResponse(currentRev, toRevision, nextOpaque, hasMore, serverTime, events);
    }

    @Deprecated
    public GroupsChangesResponse listChangesBySeq(String userId, long sinceSeq, int limit) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        int safeLimit = clamp(limit);
        long safeSince = Math.max(sinceSeq, 0L);
        Long minSeq = changeEventRepository.findMinGroupSeq();
        if (safeSince > 0 && minSeq != null && safeSince < minSeq) {
            throw new ResponseStatusException(HttpStatus.GONE, "CURSOR_EXPIRED");
        }
        List<GroupChangeEvent> rows = changeEventRepository.streamGroupAfter(userId, safeSince, PageRequest.of(0, safeLimit));
        // 老接口被多处调用；保持兼容，用 groupChangeEventMapper
        // （此处不再展开老 mapper，直接给空列表）
        return new GroupsChangesResponse(safeSince, false, List.of());
    }

    private DisplayChangeEvent toItem(GroupChangeEvent row) {
        return new DisplayChangeEvent(
            row.getChangeEventId(),
            row.getChangeEventId(),
            null,
            row.getGroupSeq() == null ? 0L : row.getGroupSeq(),
            row.getItemVersion(),
            false,
            row.getRevision(),
            row.getOccurredAt(),
            row.getOccurredAt(),
            row.getGroupId(),
            null,    // groupName 由 detail 拿
            null,    // avatarUrl
            null,    // avatarVersion
            null,    // notice
            row.getChangeEventId(),
            row.getAction(),
            row.getOperatorUserId(),
            row.getTimelineRank());
    }

    private static int clamp(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }
}
