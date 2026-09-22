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

@Service
public class MeGroupNoticeChangesService {

    public static final String DOMAIN = "groupNotices";

    private static final Logger log = LoggerFactory.getLogger(MeGroupNoticeChangesService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    public record NoticeChangeEvent(
        String id,                       // changeEventId（snapshot）/ seq 保留（events）
        String eventId,                 // events 才有
        String operation,               // events: upsert | delete
        long seq,                       // 旧字段
        long itemVersion,
        boolean deleted,
        long revision,
        long updatedAt,                 // 优先 seq-createdAt
        long occurredAt,                // 来自 row.createdAt
        String type,                    // 旧字段
        String noticeId,
        String groupId,
        String groupName,
        String groupAvatarUrl,
        String noticeType,
        String operatorUserId,
        String operatorNickName,
        String targetUserId,
        String targetNickName,
        Long lastReadAtMs) {}

    public record SnapshotResponse(
        long snapshotRevision,
        long currentRevision,
        String opaqueCursor,
        boolean hasMore,
        List<NoticeChangeEvent> items) {}

    public record ChangesResponse(
        long snapshotRevision,
        long toRevision,
        String opaqueCursor,
        boolean hasMore,
        long serverTime,
        List<NoticeChangeEvent> events) {}

    @Deprecated
    public record NoticeChangesResponse(
        long nextSeq,
        boolean hasMore,
        List<NoticeChangeEvent> events) {}

    private final GroupNoticeInboxChangeRepository changeRepository;
    private final ObjectMapper json;

    public MeGroupNoticeChangesService(GroupNoticeInboxChangeRepository changeRepository,
                                       ObjectMapper json) {
        this.changeRepository = changeRepository;
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
            Long max = changeRepository.findMaxRevisionForUser(userId);
            rev = max == null ? 0L : max;
        }
        List<GroupNoticeInboxChange> rows = changeRepository.findForUserAtRevision(
            userId, rev, PageRequest.of(0, safeLimit));
        List<NoticeChangeEvent> items = rows.stream().map(this::toItem).toList();
        Long maxRev = changeRepository.findMaxRevisionForUser(userId);
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

        Long minRev = changeRepository.findMinRevisionForUser(userId);
        if (minRev != null && sinceRevision > 0 && sinceRevision < minRev) {
            throw new ResponseStatusException(HttpStatus.GONE, "SNAPSHOT_REQUIRED");
        }

        List<GroupNoticeInboxChange> fetched = changeRepository.findForUserSinceRevision(
            userId, sinceRevision, PageRequest.of(0, safeLimit + 1));
        boolean hasMore = fetched.size() > safeLimit;
        List<GroupNoticeInboxChange> rows = hasMore ? fetched.subList(0, safeLimit) : fetched;
        List<NoticeChangeEvent> events = rows.stream().map(this::toItem).toList();
        Long maxRev = changeRepository.findMaxRevisionForUser(userId);
        long currentRev = maxRev == null ? sinceRevision : maxRev;
        long toRevision = rows.isEmpty() ? currentRev : rows.get(rows.size() - 1).getRevision();
        String nextOpaque = OpaqueCursor.encode(DOMAIN, toRevision, toRevision);
        long serverTime = System.currentTimeMillis();
        return new ChangesResponse(currentRev, toRevision, nextOpaque, hasMore, serverTime, events);
    }

    @Deprecated
    public NoticeChangesResponse listChangesBySeq(String userId, long sinceSeq, int limit) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        int safeLimit = clamp(limit);
        long safeSince = Math.max(sinceSeq, 0L);
        Long minSeq = changeRepository.findMinSeq();
        if (safeSince > 0 && minSeq != null && safeSince < minSeq) {
            throw new ResponseStatusException(HttpStatus.GONE, "CURSOR_EXPIRED");
        }
        List<GroupNoticeInboxChange> rows = changeRepository.findForUserSinceSeq(
            userId, safeSince, PageRequest.of(0, safeLimit));
        List<NoticeChangeEvent> events = rows.stream().map(this::toItem).toList();
        long nextSeq;
        if (events.isEmpty()) {
            Long max = changeRepository.findMaxSeq();
            nextSeq = max == null ? safeSince : Math.max(safeSince, max);
        } else {
            nextSeq = events.get(events.size() - 1).seq();
        }
        boolean hasMore = events.size() == safeLimit;
        return new NoticeChangesResponse(nextSeq, hasMore, events);
    }

    private NoticeChangeEvent toItem(GroupNoticeInboxChange row) {
        Map<String, Object> payload = readPayload(row.getPayloadJson());
        String type = row.getEventType();
        String op = switch (type) {
            case GroupNoticeInboxChangeWriter.TYPE_NOTICE_UPSERTED -> "upsert";
            case GroupNoticeInboxChangeWriter.TYPE_NOTICE_DELETED, GroupNoticeInboxChangeWriter.TYPE_READ_WATERMARK -> "delete";
            default -> "upsert";
        };
        boolean deleted = GroupNoticeInboxChangeWriter.TYPE_NOTICE_DELETED.equals(type);
        return new NoticeChangeEvent(
            type + ":" + row.getSeq(),
            row.getEventId(),
            op,
            row.getSeq(),
            row.getItemVersion(),
            deleted,
            row.getRevision(),
            row.getCreatedAt(),
            row.getCreatedAt(),
            type,
            row.getNoticeId(),
            str(payload.get("groupId")),
            str(payload.get("groupName")),
            str(payload.get("groupAvatarUrl")),
            firstNonBlank(str(payload.get("noticeType")), str(payload.get("type"))),
            str(payload.get("operatorUserId")),
            str(payload.get("operatorNickName")),
            str(payload.get("targetUserId")),
            str(payload.get("targetNickName")),
            longOrNull(payload.get("lastReadAtMs")));
    }

    private Map<String, Object> readPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return Map.of();
        try {
            Map<String, Object> parsed = json.readValue(payloadJson, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.debug("notice inbox payload parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private static int clamp(int limit) {
        if (limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return a != null ? a : b;
    }

    private static Long longOrNull(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v == null) return null;
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
