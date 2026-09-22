/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.user;

import com.chat99.server.realtime.FriendListRealtimePublisher;
import com.chat99.server.user.LastActiveVisibility;
import com.chat99.server.user.User;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendMutualCache;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserFriendService;
import com.chat99.server.user.UserPrivacyService;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserFriendService {
    public static final int DEFAULT_FRIEND_PAGE_LIMIT = 100;
    public static final int MAX_FRIEND_PAGE_LIMIT = 200;

    private final UserFriendRepository friendRepository;
    private final UserRepository userRepository;
    private final UserFriendMutualCache mutualCache;
    private final FriendListRealtimePublisher friendListRealtimePublisher;
    private final UserPrivacyService privacyService;
    private final MeFriendsChangesService friendsChangesService;
    private final FriendImSyncService friendImSyncService;

    public UserFriendService(UserFriendRepository friendRepository, UserRepository userRepository, UserFriendMutualCache mutualCache, FriendListRealtimePublisher friendListRealtimePublisher, UserPrivacyService privacyService, MeFriendsChangesService friendsChangesService, FriendImSyncService friendImSyncService) {
        this.friendRepository = friendRepository;
        this.userRepository = userRepository;
        this.mutualCache = mutualCache;
        this.friendListRealtimePublisher = friendListRealtimePublisher;
        this.privacyService = privacyService;
        this.friendsChangesService = friendsChangesService;
        this.friendImSyncService = friendImSyncService;
    }

    public FriendListResponse listFriends(String userId) {
        return listFriends(userId, null, null);
    }

    public FriendListResponse listFriends(String userId, Integer limit, String cursor) {
        int safeLimit = resolveFriendPageLimit(limit);
        long afterId = parseFriendCursor(cursor);
        List<UserFriend> fetched = this.friendRepository.findByUserIdAndStatusAndIdGreaterThanOrderByIdAsc(
            userId, UserFriend.STATUS_ACTIVE, afterId, PageRequest.of(0, safeLimit + 1));
        boolean hasMore = fetched.size() > safeLimit;
        List<UserFriend> rows = hasMore ? fetched.subList(0, safeLimit) : fetched;
        List<String> friendIds = rows.stream().map(UserFriend::getFriendUserId).toList();
        Map<String, UserRepository.OnlinePresenceView> presenceByUserId = friendIds.isEmpty()
            ? Map.of()
            : this.userRepository.findOnlinePresenceByUserIds(friendIds).stream()
                .collect(Collectors.toMap(UserRepository.OnlinePresenceView::getUserId, v -> v, (a, b) -> a));
        Set<String> mutualFriendIds = friendIds.isEmpty()
            ? Set.of()
            : new HashSet<>(this.friendRepository.findMutualFriendUserIdsAmong(userId, friendIds));
        List<FriendItem> items = rows.stream()
            .map(row -> this.toItem(row, presenceByUserId, mutualFriendIds))
            .toList();
        long total = this.friendRepository.countByUserIdAndStatus(userId, UserFriend.STATUS_ACTIVE);
        String nextCursor = hasMore && !rows.isEmpty()
            ? String.valueOf(rows.get(rows.size() - 1).getId())
            : null;
        long syncSeq = this.friendsChangesService.currentSyncSeq(userId);
        return new FriendListResponse(items, nextCursor, hasMore, total, syncSeq);
    }

    private static int resolveFriendPageLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_FRIEND_PAGE_LIMIT;
        }
        if (limit < 1 || limit > MAX_FRIEND_PAGE_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return limit;
    }

    private static long parseFriendCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            long id = Long.parseLong(cursor.trim());
            if (id < 0L) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            return id;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
    }

    public FriendRelationResponse getFriendRelation(String userId, String peerUserId) {
        if (peerUserId == null || peerUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (userId.equals(peerUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        Optional<UserFriendMutualCache.RelationEdges> cached =
            this.mutualCache.getRelationEdges(userId, peerUserId);
        if (cached.isPresent()) {
            UserFriendMutualCache.RelationEdges edges = cached.get();
            boolean isFriend = edges.inMyFriendList() && edges.inTheirFriendList();
            return new FriendRelationResponse(
                peerUserId, isFriend, edges.inMyFriendList(),
                edges.inMyFriendList() && !edges.inTheirFriendList(), isFriend);
        }
        this.requireActiveUser(peerUserId);
        boolean inMyFriendList = false;
        boolean inTheirFriendList = false;
        for (UserFriend edge : this.friendRepository.findActiveEdgesBetween(userId, peerUserId)) {
            if (userId.equals(edge.getUserId()) && peerUserId.equals(edge.getFriendUserId())) {
                inMyFriendList = true;
            } else if (peerUserId.equals(edge.getUserId()) && userId.equals(edge.getFriendUserId())) {
                inTheirFriendList = true;
            }
        }
        this.mutualCache.putRelationEdges(userId, peerUserId, inMyFriendList, inTheirFriendList);
        boolean isFriend = inMyFriendList && inTheirFriendList;
        return new FriendRelationResponse(
            peerUserId, isFriend, inMyFriendList, inMyFriendList && !inTheirFriendList, isFriend);
    }

    @Transactional
    public RemarkUpdateResponse updateRemark(String userId, String friendUserId, String remark) {
        String normalized;
        UserFriend row = this.requireActiveEdge(userId, friendUserId);
        String string = normalized = remark == null ? null : remark.trim();
        if (normalized != null && normalized.isBlank()) {
            normalized = null;
        }
        if (normalized != null && normalized.length() > 100) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        row.setRemark(normalized);
        this.friendRepository.save(row);
        this.friendListRealtimePublisher.remarkUpdated(userId, friendUserId);
        this.friendImSyncService.syncRemark(userId, friendUserId, normalized == null ? "" : normalized);
        return new RemarkUpdateResponse(friendUserId, normalized);
    }

    /**
     * 用户删除好友：双向完整软删（双方通讯录均移除）。
     * 旧单向删路径已停用；历史单边数据仍可能在 relation 中表现为 peerDeletedMe。
     */
    @Transactional
    public DeleteFriendResponse deleteFriendMutual(String userId, String friendUserId) {
        if (userId.equals(friendUserId)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        UserFriend row = this.friendRepository.findByUserIdAndFriendUserId(userId, friendUserId)
            .orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "FRIEND_NOT_FOUND"));
        if (row.getStatus() != 1) {
            return new DeleteFriendResponse(friendUserId, false);
        }
        softDeleteActiveEdge(row);
        softDeleteActiveEdgeIfPresent(friendUserId, userId);
        this.mutualCache.evict(userId, friendUserId);
        this.friendListRealtimePublisher.mutualDeleted(userId, friendUserId);
        this.friendImSyncService.syncDeleteBoth(userId, friendUserId);
        return new DeleteFriendResponse(friendUserId, true);
    }

    /** @deprecated 用户 API 已改为 {@link #deleteFriendMutual}；保留供兼容引用。 */
    @Deprecated
    @Transactional
    public DeleteFriendResponse deleteFriendOneWay(String userId, String friendUserId) {
        return deleteFriendMutual(userId, friendUserId);
    }

    @Transactional
    public void bindMutualFriends(String userA, String userB) {
        bindMutualFriends(userA, userB, true);
    }

    /**
     * @param syncToIm true：事务提交后同步 IM SNS（用户同意/Admin 强制加）；false：仅本地（系统号绑定）
     */
    @Transactional
    public void bindMutualFriends(String userA, String userB, boolean syncToIm) {
        if (userA == null || userB == null || userA.isBlank() || userB.isBlank()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        if (userA.equals(userB)) {
            return;
        }
        User a = this.requireActiveUser(userA);
        User b = this.requireActiveUser(userB);
        this.upsertActiveEdge(userA, userB, b);
        this.upsertActiveEdge(userB, userA, a);
        this.mutualCache.putMutual(userA, userB, true);
        this.friendListRealtimePublisher.mutualAdded(userA, userB);
        if (syncToIm) {
            this.friendImSyncService.syncAddBoth(userA, userB);
        }
    }

    public boolean isMutualActive(String userA, String userB) {
        if (userA == null || userB == null || userA.isBlank() || userB.isBlank() || userA.equals(userB)) {
            return false;
        }
        Boolean cached = this.mutualCache.getMutual(userA, userB);
        if (cached != null) {
            return cached;
        }
        boolean mutual = this.friendRepository.isMutualActive(userA, userB);
        this.mutualCache.putMutual(userA, userB, mutual);
        return mutual;
    }

    public boolean isActiveEdge(String fromUserId, String toUserId) {
        return this.friendRepository.existsByUserIdAndFriendUserIdAndStatus(fromUserId, toUserId, 1);
    }

    @Transactional
    public void reviveActiveEdge(String ownerUserId, String peerUserId) {
        User peer = this.requireActiveUser(peerUserId);
        this.upsertActiveEdge(ownerUserId, peerUserId, peer);
        this.mutualCache.evict(ownerUserId, peerUserId);
    }

    public String resolveRemark(String ownerUserId, String peerUserId) {
        return this.friendRepository.findByUserIdAndFriendUserId(ownerUserId, peerUserId).filter(r -> r.getStatus() == 1).map(UserFriend::getRemark).filter(r -> r != null && !r.isBlank()).orElse(null);
    }

    @Transactional
    public void forceDeleteMutual(String userA, String userB) {
        if (userA == null || userB == null || userA.isBlank() || userB.isBlank() || userA.equals(userB)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        boolean anyRemoved = softDeleteActiveEdgeIfPresent(userA, userB)
            | softDeleteActiveEdgeIfPresent(userB, userA);
        this.mutualCache.evict(userA, userB);
        if (anyRemoved) {
            this.friendListRealtimePublisher.mutualDeleted(userA, userB);
            this.friendImSyncService.syncDeleteBoth(userA, userB);
        }
    }

    @Transactional
    public void onUserAvatarUpdated(String userId, String avatarUrl, String avatarPreviewUrl) {
        this.friendRepository.updateAvatarByFriendUserId(userId, avatarUrl, avatarPreviewUrl, Instant.now());
        this.friendListRealtimePublisher.peerProfileUpdated(userId);
    }

    @Transactional
    public void onUserNicknameUpdated(String userId, String nickname) {
        this.friendRepository.updateNicknameByFriendUserId(userId, nickname, Instant.now());
        this.friendListRealtimePublisher.peerProfileUpdated(userId);
    }

    User requireActiveUser(String userId) {
        return this.userRepository.findByUserId(userId).filter(u -> u.getStatus() == 1).orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    User requireUserExists(String userId) {
        return this.userRepository.findByUserId(userId).orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }

    private UserFriend requireActiveEdge(String userId, String friendUserId) {
        return this.friendRepository.findByUserIdAndFriendUserId(userId, friendUserId).filter(r -> r.getStatus() == 1).orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "FRIEND_NOT_FOUND"));
    }

    private void upsertActiveEdge(String ownerUserId, String peerUserId, User peer) {
        // 含 tombstone：软删行仍占 uk_user_friend，必须 UPDATE 复活，禁止误 INSERT
        UserFriend row = this.friendRepository.findIncludingDeletedByUserIdAndFriendUserId(ownerUserId, peerUserId)
            .orElseGet(() -> {
                UserFriend created = new UserFriend();
                created.setUserId(ownerUserId);
                created.setFriendUserId(peerUserId);
                return created;
            });
        if (row.isDeleted()) {
            row.setDeleted(false);
            row.setDeletedAt(null);
            row.setItemVersion(row.getItemVersion() + 1L);
        }
        UserFriendService.applyPeerProfile(row, peer);
        row.setStatus(1);
        if (row.getAddedAt() == null && row.getImAddTime() == null) {
            row.setAddedAt(Instant.now());
        } else if (row.getAddedAt() == null) {
            row.setAddedAt(row.getImAddTime());
        }
        this.friendRepository.save(row);
    }

    private static void applyPeerProfile(UserFriend row, User peer) {
        row.setFriendNickname(peer.getNickname());
        row.setFriendAvatarUrl(peer.getAvatarUrl());
        row.setFriendAvatarPreviewUrl(peer.getAvatarPreviewUrl());
    }

    /** 完整 tombstone：status=0 + deleted=1 + deleted_at + item_version++。 */
    private void softDeleteActiveEdge(UserFriend row) {
        row.setStatus(UserFriend.STATUS_REMOVED);
        row.setDeleted(true);
        row.setDeletedAt(Instant.now());
        row.setItemVersion(row.getItemVersion() + 1L);
        this.friendRepository.save(row);
    }

    /** @return true 若确实软删了一条有效边 */
    private boolean softDeleteActiveEdgeIfPresent(String userId, String friendUserId) {
        return this.friendRepository.findByUserIdAndFriendUserId(userId, friendUserId)
            .filter(row -> row.getStatus() == UserFriend.STATUS_ACTIVE)
            .map(row -> {
                softDeleteActiveEdge(row);
                return true;
            })
            .orElse(false);
    }

    private FriendItem toItem(UserFriend row,
                              Map<String, UserRepository.OnlinePresenceView> presenceByUserId,
                              Set<String> mutualFriendIds) {
        boolean isFriend = mutualFriendIds.contains(row.getFriendUserId());
        boolean inMyFriendList = true;
        Instant addedAt = row.getAddedAt() != null ? row.getAddedAt() : row.getImAddTime();
        String remark = row.getRemark() == null ? "" : row.getRemark();
        UserRepository.OnlinePresenceView presence = presenceByUserId.get(row.getFriendUserId());
        Long lastActiveAt = presence == null ? null : this.privacyService.lastActiveAtEpochMillis(presence);
        LastActiveVisibility lastActiveVisibility = presence == null ? null : presence.getLastActiveVisibility();
        return new FriendItem(
            row.getFriendUserId(),
            row.getFriendNickname(),
            row.getFriendAvatarUrl(),
            null,
            remark,
            addedAt,
            !isFriend,
            isFriend,
            inMyFriendList,
            isFriend,
            lastActiveAt,
            lastActiveVisibility);
    }

    public record FriendItem(String friendUserId, String friendNickname, String friendAvatarUrl, Integer friendAvatarVersion, String remark, Instant addedAt, boolean peerDeletedMe, boolean canMessage, boolean inMyFriendList, boolean isFriend, Long lastActiveAt, LastActiveVisibility lastActiveVisibility) {}

    public record FriendListResponse(
        List<UserFriendService.FriendItem> items,
        String nextCursor,
        boolean hasMore,
        long total,
        long syncSeq
    ) {}

    public record FriendRelationResponse(String peerUserId, boolean isFriend, boolean inMyFriendList, boolean peerDeletedMe, boolean canMessage) {}

    public record RemarkUpdateResponse(String friendUserId, String remark) {}

    public record DeleteFriendResponse(String friendUserId, boolean deleted) {}
}
