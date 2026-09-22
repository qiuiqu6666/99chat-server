package com.chat99.server.user;

import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImAdminClient.FriendEntry;
import com.chat99.server.realtime.FriendListRealtimePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserFriendSyncService {

    private static final Logger log = LoggerFactory.getLogger(UserFriendSyncService.class);

    private final UserFriendSyncProperties props;
    private final UserFriendRepository friendRepository;
    private final UserRepository userRepository;
    private final ImAdminClient imAdmin;
    private final FriendListRealtimePublisher friendListRealtimePublisher;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long cursorAfterId;
    private volatile SyncSnapshot lastSnapshot = SyncSnapshot.idle();

    public UserFriendSyncService(UserFriendSyncProperties props,
                                 UserFriendRepository friendRepository,
                                 UserRepository userRepository,
                                 ImAdminClient imAdmin,
                                 FriendListRealtimePublisher friendListRealtimePublisher) {
        this.props = props;
        this.friendRepository = friendRepository;
        this.userRepository = userRepository;
        this.imAdmin = imAdmin;
        this.friendListRealtimePublisher = friendListRealtimePublisher;
    }

    public boolean isRunning() {
        return running.get();
    }

    public SyncSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public long cursorAfterId() {
        return cursorAfterId;
    }

    public void resetCursor() {
        cursorAfterId = 0L;
    }

    /** 异步全量同步；已在跑则返回 false。 */
    public boolean startAsync(int maxUsers, boolean resetCursor) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                SyncSnapshot snapshot = runInternal(maxUsers, resetCursor);
                lastSnapshot = snapshot;
            } catch (Exception e) {
                log.error("user friend sync failed err={}", e.getMessage(), e);
                lastSnapshot = SyncSnapshot.failed(e.getMessage());
            } finally {
                running.set(false);
            }
        }, "user-friend-sync");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** 定时 Job 单次 tick；已在跑则跳过。 */
    public SyncSnapshot runScheduledTick() {
        if (!running.compareAndSet(false, true)) {
            return lastSnapshot;
        }
        try {
            SyncSnapshot snapshot = runInternal(props.maxUsersPerScheduledRun(), false);
            lastSnapshot = snapshot;
            return snapshot;
        } finally {
            running.set(false);
        }
    }

    @Transactional
    public UserSyncResult syncUserFriends(String userId) {
        int total = imAdmin.friendCount(userId);
        int fetchLimit = total > 0 ? total : 100;
        List<FriendEntry> friends = imAdmin.listFriends(userId, fetchLimit);
        Instant now = Instant.now();
        int inserted = 0;
        int updated = 0;
        int revived = 0;

        for (FriendEntry friend : friends) {
            if (friend.friendUid() == null || friend.friendUid().isBlank()) {
                continue;
            }
            String friendUserId = friend.friendUid().trim();
            // 对账查询含 tombstone 行：IM 仍存在的好友若本地软删，复活（IM 为准）
            var existing = friendRepository.findIncludingDeletedByUserIdAndFriendUserId(userId, friendUserId);
            if (existing.isPresent()) {
                UserFriend row = existing.get();
                int beforeStatus = row.getStatus();
                boolean wasDeleted = row.isDeleted();
                if (wasDeleted) {
                    // 复活 tombstone：IM 侧仍是好友 → deleted=0 + item_version++
                    row.setDeleted(false);
                    row.setDeletedAt(null);
                    row.setItemVersion(row.getItemVersion() + 1L);
                    revived++;
                }
                if (applyFriendFields(row, friend, now)) {
                    updated++;
                }
                friendRepository.save(row);
                emitMutualAddedIfNewlyEstablished(userId, friendUserId, beforeStatus != UserFriend.STATUS_ACTIVE || wasDeleted);
            } else {
                UserFriend row = new UserFriend();
                row.setUserId(userId);
                row.setFriendUserId(friendUserId);
                applyFriendFields(row, friend, now);
                friendRepository.save(row);
                inserted++;
                emitMutualAddedIfNewlyEstablished(userId, friendUserId, true);
            }
        }

        // IM 同步仅 upsert 资料/备注，不自动清理本地好友边；删除仅走 DELETE /me/friends。
        return new UserSyncResult(userId, friends.size(), inserted, updated, revived, 0);
    }

    private SyncSnapshot runInternal(int maxUsers, boolean resetCursor) {
        if (resetCursor) {
            cursorAfterId = 0L;
        }
        int limit = maxUsers <= 0 ? Integer.MAX_VALUE : maxUsers;
        int scanned = 0;
        int usersOk = 0;
        int usersFail = 0;
        int totalInserted = 0;
        int totalUpdated = 0;
        int totalRevived = 0;
        int totalRemoved = 0;
        long startCursor = cursorAfterId;

        while (scanned < limit) {
            int pageSize = Math.min(props.batchSize(), limit - scanned);
            Page<User> page = userRepository.findByStatusAndIdGreaterThanOrderByIdAsc(
                1, cursorAfterId, PageRequest.of(0, pageSize));
            if (page.isEmpty()) {
                cursorAfterId = 0L;
                break;
            }
            for (User user : page) {
                cursorAfterId = user.getId();
                scanned++;
                try {
                    UserSyncResult result = syncUserFriends(user.getUserId());
                    usersOk++;
                    totalInserted += result.inserted();
                    totalUpdated += result.updated();
                    totalRevived += result.revived();
                    totalRemoved += result.removed();
                } catch (Exception e) {
                    usersFail++;
                    log.warn("user friend sync failed userId={} err={}", user.getUserId(), e.getMessage());
                }
                sleepQuiet(props.delayBetweenUsersMs());
                if (scanned >= limit) {
                    break;
                }
            }
            if (scanned >= limit) {
                break;
            }
        }

        boolean completed = cursorAfterId == 0L;
        SyncSnapshot snapshot = new SyncSnapshot(
            true, completed, startCursor, cursorAfterId, scanned, usersOk, usersFail,
            totalInserted, totalUpdated, totalRevived, totalRemoved, null);
        log.info(
            "user friend sync scanned={} ok={} fail={} inserted={} updated={} revived={} removed={} completed={} cursor={}",
            scanned, usersOk, usersFail, totalInserted, totalUpdated, totalRevived, totalRemoved, completed, cursorAfterId);
        return snapshot;
    }

    private static boolean applyFriendFields(UserFriend row, FriendEntry friend, Instant syncedAt) {
        boolean changed = false;
        if (!Objects.equals(row.getFriendNickname(), friend.nickname())) {
            row.setFriendNickname(friend.nickname());
            changed = true;
        }
        if (!Objects.equals(row.getFriendAvatarUrl(), friend.avatarUrl())) {
            String avatarUrl = friend.avatarUrl();
            row.setFriendAvatarUrl(avatarUrl);
            if (avatarUrl != null && avatarUrl.contains("_thumb.jpg")) {
                row.setFriendAvatarPreviewUrl(avatarUrl.replace("_thumb.jpg", "_preview.jpg"));
            } else if (avatarUrl != null && avatarUrl.contains("_preview.jpg")) {
                row.setFriendAvatarPreviewUrl(avatarUrl);
                row.setFriendAvatarUrl(avatarUrl.replace("_preview.jpg", "_thumb.jpg"));
            }
            changed = true;
        }
        String imRemark = friend.remark();
        if (imRemark != null && !imRemark.isBlank()) {
            String normalized = imRemark.trim();
            if (normalized.length() > 100) {
                normalized = normalized.substring(0, 100);
            }
            if (!Objects.equals(row.getRemark(), normalized)) {
                row.setRemark(normalized);
                changed = true;
            }
        }
        Instant imAddTime = friend.addTimeSec() != null && friend.addTimeSec() > 0
            ? Instant.ofEpochSecond(friend.addTimeSec()) : null;
        if (!Objects.equals(row.getImAddTime(), imAddTime)) {
            row.setImAddTime(imAddTime);
            changed = true;
        }
        if (row.getStatus() != UserFriend.STATUS_ACTIVE) {
            row.setStatus(UserFriend.STATUS_ACTIVE);
            changed = true;
        }
        row.setSyncedAt(syncedAt);
        return changed;
    }

    /**
     * IM 好友同步阶段，若首次建立双向好友关系，补发实时/离线事件。
     * 使用 userId 字典序去重，避免 A/B 两侧同步时重复推送。
     */
    private void emitMutualAddedIfNewlyEstablished(String userId, String friendUserId, boolean becameActiveNow) {
        if (!becameActiveNow) {
            return;
        }
        if (userId == null || friendUserId == null || userId.isBlank() || friendUserId.isBlank()) {
            return;
        }
        if (userId.compareTo(friendUserId) >= 0) {
            return;
        }
        if (!friendRepository.existsByUserIdAndFriendUserIdAndStatus(
            friendUserId, userId, UserFriend.STATUS_ACTIVE)) {
            return;
        }
        friendListRealtimePublisher.mutualAdded(userId, friendUserId);
    }

    private static void sleepQuiet(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record UserSyncResult(
        String userId, int imFriendCount, int inserted, int updated, int revived, int removed) {}

    public record SyncSnapshot(
        boolean ok,
        boolean completed,
        long startCursor,
        long endCursor,
        int scanned,
        int usersOk,
        int usersFail,
        int inserted,
        int updated,
        int revived,
        int removed,
        String error) {

        static SyncSnapshot idle() {
            return new SyncSnapshot(true, true, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }

        static SyncSnapshot failed(String error) {
            return new SyncSnapshot(false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, error);
        }
    }
}
