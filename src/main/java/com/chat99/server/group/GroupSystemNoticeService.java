package com.chat99.server.group;

import com.chat99.server.realtime.GroupRealtimePublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GroupSystemNoticeService {

    public record GroupSystemNoticeView(
        String noticeId,
        String groupId,
        String groupName,
        String groupAvatarUrl,
        String type,
        String operatorUserId,
        String operatorNickName,
        String targetUserId,
        String targetNickName,
        long createdAt) {}

    public record GroupSystemNoticeListResponse(
        List<GroupSystemNoticeView> items,
        long total,
        int limit,
        int offset,
        long unreadCount,
        Long lastReadAtMs) {}

    public record DeleteNoticesResponse(int deleted) {}

    private static final Duration DEDUP_WINDOW = Duration.ofSeconds(60);
    /** 批量写入 dismiss / 查已存在 IN 分块大小 */
    private static final int DISMISS_CHUNK = 500;

    private final GroupSystemNoticeRepository noticeRepository;
    private final GroupSystemNoticeDismissRepository dismissRepository;
    private final GroupNoticeReadStateRepository readStateRepository;
    private final GroupProfileRepository profileRepository;
    private final GroupProjectionService projection;
    private final GroupMemberEnrichmentService enrichment;
    private final GroupAvatarDefaults avatarDefaults;
    private final GroupRealtimePublisher groupRealtime;
    private final GroupSystemNoticesListCache listCache;
    private final GroupNoticeInboxChangeWriter inboxWriter;

    public GroupSystemNoticeService(GroupSystemNoticeRepository noticeRepository,
                                    GroupSystemNoticeDismissRepository dismissRepository,
                                    GroupNoticeReadStateRepository readStateRepository,
                                    GroupProfileRepository profileRepository,
                                    GroupProjectionService projection,
                                    GroupMemberEnrichmentService enrichment,
                                    GroupAvatarDefaults avatarDefaults,
                                    GroupRealtimePublisher groupRealtime,
                                    GroupSystemNoticesListCache listCache,
                                    GroupNoticeInboxChangeWriter inboxWriter) {
        this.noticeRepository = noticeRepository;
        this.dismissRepository = dismissRepository;
        this.readStateRepository = readStateRepository;
        this.profileRepository = profileRepository;
        this.projection = projection;
        this.enrichment = enrichment;
        this.avatarDefaults = avatarDefaults;
        this.groupRealtime = groupRealtime;
        this.listCache = listCache;
        this.inboxWriter = inboxWriter;
    }

    public GroupSystemNoticeListResponse listForUser(String userId, int limit, int offset,
                                                     long sinceMs, boolean unreadOnly) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        return listCache.get(userId, safeLimit, safeOffset, sinceMs, unreadOnly)
            .orElseGet(() -> loadAndCache(userId, safeLimit, safeOffset, sinceMs, unreadOnly));
    }

    private GroupSystemNoticeListResponse loadAndCache(String userId, int safeLimit, int safeOffset,
                                                       long sinceMs, boolean unreadOnly) {
        Instant since = sinceMs > 0 ? Instant.ofEpochMilli(sinceMs) : null;
        Instant readAt = resolveReadAt(userId);
        Long lastReadAtMs = readAt == null ? null : readAt.toEpochMilli();
        Instant unreadAfter = unreadOnly ? readAt : null;
        Page<GroupSystemNotice> page = noticeRepository.findForUser(
            userId, since, unreadAfter, PageRequest.of(safeOffset / safeLimit, safeLimit));
        List<GroupSystemNotice> rows = page.getContent();
        Map<String, GroupProfile> profiles = loadProfiles(rows);
        Map<String, GroupMemberEnrichmentService.UserBrief> userBriefs = loadUserBriefs(rows);
        List<GroupSystemNoticeView> items = rows.stream()
            .map(row -> toView(row, profiles, userBriefs))
            .toList();
        long unreadCount = noticeRepository.countUnreadAfter(userId, readAt == null ? Instant.EPOCH : readAt);
        GroupSystemNoticeListResponse body = new GroupSystemNoticeListResponse(
            items, page.getTotalElements(), safeLimit, safeOffset, unreadCount, lastReadAtMs);
        listCache.put(userId, safeLimit, safeOffset, sinceMs, unreadOnly, body);
        return body;
    }

    @Transactional
    public void markRead(String userId, long readAtMs) {
        Instant readAt = readAtMs > 0 ? Instant.ofEpochMilli(readAtMs) : Instant.now();
        GroupNoticeReadState state = readStateRepository.findById(userId).orElseGet(() -> {
            GroupNoticeReadState created = new GroupNoticeReadState();
            created.setUserId(userId);
            return created;
        });
        state.setReadAt(readAt);
        readStateRepository.save(state);
        inboxWriter.writeReadWatermark(userId, readAt.toEpochMilli());
        listCache.invalidateUser(userId);
    }

    @Transactional
    public DeleteNoticesResponse dismissNotice(String userId, String noticeId) {
        return dismissNotices(userId, List.of(noticeId));
    }

    @Transactional
    public DeleteNoticesResponse dismissNotices(String userId, List<String> noticeIds) {
        List<String> ids = normalizeNoticeIds(noticeIds);
        List<String> candidates;
        boolean byIds = !ids.isEmpty();
        if (byIds) {
            candidates = resolveVisibleNoticeIds(userId, ids);
        } else {
            candidates = noticeRepository.findVisibleUndismissedNoticeIds(userId);
        }
        List<String> newlyDismissed = insertNewDismissals(userId, candidates);
        int dismissed = newlyDismissed.size();
        if (byIds && dismissed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND");
        }
        for (String noticeId : newlyDismissed) {
            long seq = inboxWriter.writeDeleted(userId, noticeId).seq();
            publishDeletedTcp(userId, noticeId, seq);
        }
        listCache.invalidateUser(userId);
        return new DeleteNoticesResponse(dismissed);
    }

    private List<String> resolveVisibleNoticeIds(String userId, List<String> noticeIds) {
        LinkedHashSet<String> visible = new LinkedHashSet<>();
        for (int from = 0; from < noticeIds.size(); from += DISMISS_CHUNK) {
            int to = Math.min(from + DISMISS_CHUNK, noticeIds.size());
            List<String> chunk = noticeIds.subList(from, to);
            for (GroupSystemNotice notice : noticeRepository.findAllById(chunk)) {
                if (notice != null && canViewInMyNotices(userId, notice)) {
                    visible.add(notice.getNoticeId());
                }
            }
        }
        return new ArrayList<>(visible);
    }

    /** @return 本次新写入 dismiss 的 noticeId 列表（有序） */
    private List<String> insertNewDismissals(String userId, List<String> candidateNoticeIds) {
        if (candidateNoticeIds == null || candidateNoticeIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> toInsert = new LinkedHashSet<>(candidateNoticeIds);
        for (int from = 0; from < candidateNoticeIds.size(); from += DISMISS_CHUNK) {
            int to = Math.min(from + DISMISS_CHUNK, candidateNoticeIds.size());
            List<String> chunk = candidateNoticeIds.subList(from, to);
            toInsert.removeAll(dismissRepository.findNoticeIdsByUserIdAndNoticeIdIn(userId, chunk));
        }
        if (toInsert.isEmpty()) {
            return List.of();
        }
        List<String> ordered = new ArrayList<>(toInsert);
        dismissRepository.insertIgnoreBatch(userId, ordered, Instant.now());
        return ordered;
    }

    private static boolean canViewInMyNotices(String userId, GroupSystemNotice notice) {
        return userId.equals(notice.getOperatorUserId()) || userId.equals(notice.getTargetUserId());
    }

    private static List<String> normalizeNoticeIds(List<String> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty()) {
            return List.of();
        }
        return noticeIds.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .distinct()
            .toList();
    }

    @Transactional
    public void recordAndPublish(String groupId,
                                 GroupSystemNoticeType type,
                                 String operatorUserId,
                                 String targetUserId) {
        if (groupId == null || groupId.isBlank()
            || operatorUserId == null || operatorUserId.isBlank()
            || targetUserId == null || targetUserId.isBlank()) {
            return;
        }
        Instant dedupSince = Instant.now().minus(DEDUP_WINDOW);
        if (noticeRepository.existsRecent(
            groupId.trim(), type, operatorUserId.trim(), targetUserId.trim(), dedupSince)) {
            return;
        }
        Instant createdAt = Instant.now();
        String noticeId = buildNoticeId(type, groupId, operatorUserId, targetUserId, createdAt);
        if (noticeRepository.existsById(noticeId)) {
            return;
        }
        GroupSystemNotice row = new GroupSystemNotice();
        row.setNoticeId(noticeId);
        row.setGroupId(groupId);
        row.setType(type);
        row.setOperatorUserId(operatorUserId);
        row.setTargetUserId(targetUserId);
        row.setCreatedAt(createdAt);
        noticeRepository.save(row);

        Map<String, Object> snapshot = buildSnapshotPayload(row);
        for (String uid : resolveTargets(type, operatorUserId, targetUserId)) {
            long seq = inboxWriter.writeUpserted(uid, noticeId, snapshot).seq();
            publishUpsertedTcp(uid, row, snapshot, seq);
            listCache.invalidateUser(uid);
        }
    }

    private Map<String, Object> buildSnapshotPayload(GroupSystemNotice row) {
        Map<String, GroupProfile> profiles = loadProfiles(List.of(row));
        Map<String, GroupMemberEnrichmentService.UserBrief> briefs = loadUserBriefs(List.of(row));
        GroupSystemNoticeView view = toView(row, profiles, briefs);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("noticeId", view.noticeId());
        payload.put("groupId", view.groupId());
        payload.put("groupName", view.groupName());
        payload.put("groupAvatarUrl", view.groupAvatarUrl());
        payload.put("type", view.type());
        payload.put("noticeType", view.type());
        payload.put("operatorUserId", view.operatorUserId());
        payload.put("operatorNickName", view.operatorNickName());
        payload.put("targetUserId", view.targetUserId());
        payload.put("targetNickName", view.targetNickName());
        payload.put("createdAtMs", view.createdAt());
        payload.put("createdAt", view.createdAt());
        return payload;
    }

    private void publishUpsertedTcp(String userId,
                                    GroupSystemNotice row,
                                    Map<String, Object> snapshot,
                                    long seq) {
        if (seq <= 0 || userId == null || userId.isBlank()) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>(snapshot);
        detail.put("seq", seq);
        long occurredAtMs = row.getCreatedAt() == null
            ? System.currentTimeMillis()
            : row.getCreatedAt().toEpochMilli();
        String changeEventId = GroupChangeIdGenerator.newChangeEventId();
        groupRealtime.publish(
            row.getGroupId(),
            GroupRealtimePublisher.ACTION_GROUP_SYSTEM_NOTICE,
            row.getOperatorUserId(),
            List.of(),
            List.of(userId),
            detail,
            changeEventId,
            occurredAtMs,
            GroupTimelineRank.forAction(GroupRealtimePublisher.ACTION_GROUP_SYSTEM_NOTICE));
    }

    private void publishDeletedTcp(String userId, String noticeId, long seq) {
        if (seq <= 0 || userId == null || userId.isBlank() || noticeId == null || noticeId.isBlank()) {
            return;
        }
        GroupSystemNotice row = noticeRepository.findById(noticeId).orElse(null);
        String groupId = row == null ? "_" : row.getGroupId();
        String operatorUserId = row == null ? userId : row.getOperatorUserId();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", GroupNoticeInboxChangeWriter.TYPE_NOTICE_DELETED);
        detail.put("noticeType", GroupNoticeInboxChangeWriter.TYPE_NOTICE_DELETED);
        detail.put("noticeId", noticeId);
        detail.put("seq", seq);
        if (row != null) {
            detail.put("groupId", row.getGroupId());
        }
        groupRealtime.publish(
            groupId,
            GroupRealtimePublisher.ACTION_GROUP_SYSTEM_NOTICE,
            operatorUserId,
            List.of(),
            List.of(userId),
            detail,
            GroupChangeIdGenerator.newChangeEventId(),
            System.currentTimeMillis(),
            null);
    }

    private static List<String> resolveTargets(GroupSystemNoticeType type,
                                               String operatorUserId,
                                               String targetUserId) {
        Set<String> targets = new LinkedHashSet<>();
        if (type == GroupSystemNoticeType.transfer_owner) {
            targets.add(targetUserId);
            if (operatorUserId != null && !operatorUserId.isBlank()) {
                targets.add(operatorUserId.trim());
            }
        } else {
            if (operatorUserId != null && !operatorUserId.isBlank()) {
                targets.add(operatorUserId.trim());
            }
            if (targetUserId != null && !targetUserId.isBlank()) {
                targets.add(targetUserId.trim());
            }
        }
        return new ArrayList<>(targets);
    }

    private GroupSystemNoticeView toView(GroupSystemNotice row,
                                         Map<String, GroupProfile> profiles,
                                         Map<String, GroupMemberEnrichmentService.UserBrief> briefs) {
        GroupProfile profile = profiles.get(row.getGroupId());
        String groupName = profile == null ? null : profile.getGroupName();
        String groupAvatarUrl = profile == null
            ? null
            : avatarDefaults.resolve(profile.getAvatarUrl());
        return new GroupSystemNoticeView(
            row.getNoticeId(),
            row.getGroupId(),
            groupName,
            groupAvatarUrl,
            row.getType().name(),
            row.getOperatorUserId(),
            nickName(row.getOperatorUserId(), briefs),
            row.getTargetUserId(),
            nickName(row.getTargetUserId(), briefs),
            row.getCreatedAt().toEpochMilli());
    }

    private Map<String, GroupProfile> loadProfiles(List<GroupSystemNotice> rows) {
        Map<String, GroupProfile> out = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        Set<String> groupIds = new LinkedHashSet<>();
        for (GroupSystemNotice row : rows) {
            if (row.getGroupId() != null && !row.getGroupId().isBlank()) {
                groupIds.add(row.getGroupId());
            }
        }
        if (groupIds.isEmpty()) {
            return out;
        }
        for (GroupProfile profile : profileRepository.findAllById(groupIds)) {
            if (profile != null && !profile.isDismissed()) {
                out.put(profile.getGroupId(), profile);
            }
        }
        return out;
    }

    private Map<String, GroupMemberEnrichmentService.UserBrief> loadUserBriefs(List<GroupSystemNotice> rows) {
        Set<String> userIds = new LinkedHashSet<>();
        for (GroupSystemNotice row : rows) {
            userIds.add(row.getOperatorUserId());
            userIds.add(row.getTargetUserId());
        }
        return enrichment.loadUserBriefs(userIds);
    }

    private Instant resolveReadAt(String userId) {
        return readStateRepository.findById(userId)
            .map(GroupNoticeReadState::getReadAt)
            .orElse(null);
    }

    static String buildNoticeId(GroupSystemNoticeType type,
                                String groupId,
                                String operatorUserId,
                                String targetUserId,
                                Instant createdAt) {
        return type.name() + "|" + groupId + "|" + operatorUserId + "|" + targetUserId + "|"
            + createdAt.toEpochMilli();
    }

    private static String nickName(String userId,
                                   Map<String, GroupMemberEnrichmentService.UserBrief> briefs) {
        if (userId == null || briefs == null) {
            return null;
        }
        GroupMemberEnrichmentService.UserBrief brief = briefs.get(userId);
        return brief == null ? null : brief.nickname();
    }
}
