package com.chat99.server.user;

import com.chat99.server.sync.OpaqueCursor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 好友通讯录 Difference 接口（v2 协议）：
 * <ul>
 *   <li>{@code GET /me/friends/snapshot?limit=...&snapshotRevision=...}
 *       返回当前快照一致副本</li>
 *   <li>{@code GET /me/friends/changes?sinceCursor=&limit=...}
 *       增量返回 sinceCursor 之后的所有事件，opaqueCursor 防伪造</li>
 * </ul>
 * <p>游标过期（sinceRevision 比当前最小还小）返回 HTTP 410 {@code SNAPSHOT_REQUIRED}，
 * 客户端应回退 {@code /me/friends/snapshot} 拉全量。
 * opaque cursor 解析失败返回 HTTP 410 {@code INVALID_CURSOR}。
 */
@Service
public class MeFriendsChangesService {

    private static final Logger log = LoggerFactory.getLogger(MeFriendsChangesService.class);
    public static final String DOMAIN = "contacts";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    /** 单条事件 / 单条快照项的统一视图。{@code deleted} tombstone 表示已删除。 */
    public record FriendItem(
        String id,                   // peerUserId
        String eventId,              // 仅增量事件有；快照项无（留空）
        String operation,            // 仅增量：upsert | delete；快照：空
        long itemVersion,
        boolean deleted,
        long updatedAt,
        String peerNickname,
        String peerAvatarUrl,
        String remark,
        Boolean inMyFriendList,
        Boolean isFriend,
        Boolean peerDeletedMe,
        Boolean canMessage,
        Long lastActiveAt,
        String lastActiveVisibility,
        String tcpAction) {}

    public record SnapshotResponse(
        long snapshotRevision,
        String opaqueCursor,
        boolean hasMore,
        long total,
        List<FriendItem> items) {}

    public record ChangesResponse(
        long snapshotRevision,
        long toRevision,
        String opaqueCursor,
        boolean hasMore,
        long serverTime,
        List<FriendItem> events) {}

    private final FriendContactChangeRepository changeRepository;
    private final UserFriendRepository friendRepository;
    private final UserRepository userRepository;
    private final UserPrivacyService privacyService;
    private final ObjectMapper json;

    public MeFriendsChangesService(FriendContactChangeRepository changeRepository,
                                    UserFriendRepository friendRepository,
                                    UserRepository userRepository,
                                    UserPrivacyService privacyService,
                                    ObjectMapper json) {
        this.changeRepository = changeRepository;
        this.friendRepository = friendRepository;
        this.userRepository = userRepository;
        this.privacyService = privacyService;
        this.json = json;
    }

    /**
     * Contacts snapshot is reconstructed exclusively from the current user_friend relation table.
     * friend_contact_change is an append-only Difference stream and must never be used as a
     * snapshot projection source.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SnapshotResponse snapshot(String accountId, String opaqueCursor, int limit, Long snapshotRevision) {
        if (accountId == null || accountId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        int safeLimit = clamp(limit);
        OpaqueCursor.CursorPayload cursor;
        try {
            cursor = OpaqueCursor.decode(opaqueCursor);
            OpaqueCursor.validate(cursor, DOMAIN);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.GONE, "INVALID_CURSOR");
        }

        Long max = changeRepository.findMaxRevisionForAccount(accountId);
        long currentRevision = max == null ? 0L : max;
        long revision;
        String afterFriendUserId;
        long total;
        long emitted;
        if (cursor.lastKey() != null) {
            revision = cursor.revision();
            total = cursor.total() == null ? -1L : cursor.total();
            emitted = cursor.emitted() == null ? -1L : cursor.emitted();
            afterFriendUserId = cursor.lastKey();
            if (revision != currentRevision || (snapshotRevision != null && snapshotRevision != revision)
                || total < 0L || emitted < 0L) {
                throw new ResponseStatusException(HttpStatus.GONE, "SNAPSHOT_EXPIRED");
            }
        } else {
            revision = snapshotRevision != null && snapshotRevision > 0 ? snapshotRevision : currentRevision;
            if (revision != currentRevision) {
                throw new ResponseStatusException(HttpStatus.GONE, "SNAPSHOT_EXPIRED");
            }
            afterFriendUserId = "";
            total = friendRepository.countCurrentSnapshotFriends(accountId);
            emitted = 0L;
        }

        List<UserFriend> fetched = friendRepository.findCurrentSnapshotFriends(
            accountId, afterFriendUserId, PageRequest.of(0, safeLimit + 1));
        boolean hasMore = fetched.size() > safeLimit;
        List<UserFriend> rows = hasMore ? fetched.subList(0, safeLimit) : fetched;
        List<FriendItem> items = toSnapshotItems(accountId, rows);
        long nextEmitted = emitted + items.size();
        if (!hasMore && nextEmitted != total) {
            log.error("CONTACT_SNAPSHOT_INCOMPLETE account={} revision={} total={} emitted={}",
                accountId, revision, total, nextEmitted);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "CONTACT_SNAPSHOT_INCOMPLETE");
        }
        String nextOpaque = hasMore && !rows.isEmpty()
            ? OpaqueCursor.encode(DOMAIN, revision, rows.get(rows.size() - 1).getFriendUserId(), total, nextEmitted)
            : "";
        return new SnapshotResponse(revision, nextOpaque, hasMore, total, items);
    }

    private List<FriendItem> toSnapshotItems(String accountId, List<UserFriend> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> peerIds = rows.stream().map(UserFriend::getFriendUserId).toList();
        Map<String, User> peers = new HashMap<>();
        for (User peer : userRepository.findByUserIdIn(peerIds)) {
            peers.put(peer.getUserId(), peer);
        }
        Set<String> mutual = new HashSet<>(friendRepository.findMutualFriendUserIdsAmong(accountId, peerIds));
        return rows.stream().map(row -> {
            User peer = peers.get(row.getFriendUserId());
            boolean isFriend = mutual.contains(row.getFriendUserId());
            Long lastActiveAt = peer == null ? null : privacyService.lastActiveAtEpochMillis(peer);
            String visibility = peer == null || privacyService.lastActiveVisibilityOf(peer) == null
                ? null : privacyService.lastActiveVisibilityOf(peer).name();
            Instant updated = row.getUpdatedAt() == null ? row.getCreatedAt() : row.getUpdatedAt();
            return new FriendItem(
                row.getFriendUserId(),
                "snapshot:" + accountId + ":" + row.getFriendUserId() + ":" + row.getItemVersion(),
                "upsert",
                row.getItemVersion(),
                false,
                updated == null ? 0L : updated.toEpochMilli(),
                row.getFriendNickname(),
                row.getFriendAvatarUrl(),
                row.getRemark(),
                true,
                isFriend,
                !isFriend,
                isFriend,
                lastActiveAt,
                visibility,
                "updated");
        }).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChangesResponse changes(String accountId, String opaqueCursor, int limit) {
        if (accountId == null || accountId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        if (opaqueCursor == null || opaqueCursor.isBlank()) {
            opaqueCursor = OpaqueCursor.empty(DOMAIN);
        }
        OpaqueCursor.CursorPayload payload;
        try {
            payload = OpaqueCursor.decode(opaqueCursor);
            OpaqueCursor.validate(payload, DOMAIN);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.GONE, "INVALID_CURSOR");
        }

        int safeLimit = clamp(limit);
        long sinceRevision = payload.revision();

        Long minRev = changeRepository.findMinRevisionForAccount(accountId);
        if (minRev != null && sinceRevision > 0 && sinceRevision < minRev) {
            throw new ResponseStatusException(HttpStatus.GONE, "SNAPSHOT_REQUIRED");
        }

        List<FriendContactChange> fetched = changeRepository.findForAccountSinceRevision(
            accountId, sinceRevision, PageRequest.of(0, safeLimit + 1));
        boolean hasMore = fetched.size() > safeLimit;
        List<FriendContactChange> rows = hasMore ? fetched.subList(0, safeLimit) : fetched;
        List<FriendItem> events = rows.stream().map(this::toItem).toList();
        Long maxRev = changeRepository.findMaxRevisionForAccount(accountId);
        long currentRev = maxRev == null ? sinceRevision : maxRev;
        // Only acknowledge rows actually read. A concurrent commit must never be
        // skipped merely because MAX(revision) sees it after the page query.
        long toRevision = rows.isEmpty() ? sinceRevision : rows.get(rows.size() - 1).getRevision();
        String nextOpaque = OpaqueCursor.encode(DOMAIN, toRevision, toRevision);
        long serverTime = System.currentTimeMillis();
        return new ChangesResponse(currentRev, toRevision, nextOpaque, hasMore, serverTime, events);
    }

    /**
     * @deprecated 旧协议：since_seq 数字 cursor。保留向后兼容，新客户端应改用 changes(opaqueCursor)。
     */
    @Deprecated
    public record FriendsChangesResponse(
        long nextSeq,
        boolean hasMore,
        long total,
        List<FriendChangeEvent> events) {}

    @Deprecated
    public record FriendChangeEvent(
        long seq,
        String type,
        String peerUserId,
        String peerNickname,
        String peerAvatarUrl,
        String remark,
        Boolean inMyFriendList,
        Boolean isFriend,
        Boolean peerDeletedMe,
        Boolean canMessage,
        Long lastActiveAt,
        String lastActiveVisibility,
        String tcpAction) {}

    @Deprecated
    public FriendsChangesResponse listChangesBySeq(String accountId, long sinceSeq, int limit) {
        if (accountId == null || accountId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        int safeLimit = clamp(limit);
        long safeSince = Math.max(sinceSeq, 0L);

        Long minSeq = changeRepository.findMinSeqForAccount(accountId);
        if (safeSince > 0 && minSeq != null && safeSince < minSeq) {
            throw new ResponseStatusException(HttpStatus.GONE, "SNAPSHOT_REQUIRED");
        }

        List<FriendContactChange> rows = changeRepository.findForAccountSinceSeq(
            accountId, safeSince, PageRequest.of(0, safeLimit));
        List<FriendChangeEvent> events = rows.stream().map(this::toLegacyEvent).toList();
        long nextSeq;
        if (events.isEmpty()) {
            Long max = changeRepository.findMaxSeqForAccount(accountId);
            nextSeq = max == null ? safeSince : Math.max(safeSince, max);
        } else {
            nextSeq = events.get(events.size() - 1).seq();
        }
        boolean hasMore = events.size() == safeLimit;
        long total = friendRepository.countByUserIdAndStatus(accountId, UserFriend.STATUS_ACTIVE);
        return new FriendsChangesResponse(nextSeq, hasMore, total, events);
    }

    public long currentSyncSeq(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return 0L;
        }
        Long max = changeRepository.findMaxSeqForAccount(accountId);
        return max == null ? 0L : max;
    }

    public long currentRevision(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return 0L;
        }
        Long max = changeRepository.findMaxRevisionForAccount(accountId);
        return max == null ? 0L : max;
    }

    /** change 表行 → 统一 FriendItem。 */
    private FriendItem toItem(FriendContactChange row) {
        Map<String, Object> payload = readPayload(row.getPayloadJson());
        String op = switch (row.getEventType()) {
            case FriendContactChangeWriter.TYPE_CONTACT_CREATED -> "upsert";
            case FriendContactChangeWriter.TYPE_CONTACT_DELETED -> "delete";
            default -> "upsert";
        };
        return new FriendItem(
            row.getPeerUserId(),
            row.getEventId(),
            op,
            row.getItemVersion(),
            row.isDeleted(),
            row.getCreatedAt(),
            str(payload.get("peerNickname")),
            str(payload.get("peerAvatarUrl")),
            str(payload.get("remark")),
            boolOrNull(payload.get("inMyFriendList")),
            boolOrNull(payload.get("isFriend")),
            boolOrNull(payload.get("peerDeletedMe")),
            boolOrNull(payload.get("canMessage")),
            longOrNull(payload.get("lastActiveAt")),
            str(payload.get("lastActiveVisibility")),
            str(payload.get("tcpAction")));
    }

    private FriendChangeEvent toLegacyEvent(FriendContactChange row) {
        Map<String, Object> payload = readPayload(row.getPayloadJson());
        return new FriendChangeEvent(
            row.getSeq(),
            row.getEventType(),
            row.getPeerUserId(),
            str(payload.get("peerNickname")),
            str(payload.get("peerAvatarUrl")),
            str(payload.get("remark")),
            boolOrNull(payload.get("inMyFriendList")),
            boolOrNull(payload.get("isFriend")),
            boolOrNull(payload.get("peerDeletedMe")),
            boolOrNull(payload.get("canMessage")),
            longOrNull(payload.get("lastActiveAt")),
            str(payload.get("lastActiveVisibility")),
            str(payload.get("tcpAction")));
    }

    private Map<String, Object> readPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = json.readValue(payloadJson, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.debug("friend contact change payload parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Boolean boolOrNull(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return null;
        return Boolean.parseBoolean(v.toString());
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
