package com.chat99.server.realtime;

import com.chat99.server.user.FriendContactChangeWriter;
import com.chat99.server.user.LastActiveVisibility;
import com.chat99.server.user.User;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserPrivacyService;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class FriendListRealtimePublisher {

    public static final String EVENT = "friend_list_changed";
    public static final String ACTION_ADDED = "added";
    public static final String ACTION_REMOVED = "removed";
    public static final String ACTION_UPDATED = "updated";
    public static final String ACTION_PROFILE_UPDATED = "profile_updated";
    public static final String ACTION_REMARK_UPDATED = "remark_updated";

    public record FriendListChangedCommittedEvent(
        String targetUserId,
        String action,
        String peerUserId,
        String peerNickname,
        String peerAvatarUrl,
        String remark,
        Instant addedAt,
        boolean inMyFriendList,
        boolean isFriend,
        boolean peerDeletedMe,
        boolean canMessage,
        Long lastActiveAt,
        LastActiveVisibility lastActiveVisibility,
        long seq) {}

    private final ApplicationEventPublisher events;
    private final UserFriendRepository friendRepository;
    private final UserRepository userRepository;
    private final UserPrivacyService privacyService;
    private final FriendContactChangeWriter changeWriter;

    public FriendListRealtimePublisher(ApplicationEventPublisher events,
                                       UserFriendRepository friendRepository,
                                       UserRepository userRepository,
                                       UserPrivacyService privacyService,
                                       FriendContactChangeWriter changeWriter) {
        this.events = events;
        this.friendRepository = friendRepository;
        this.userRepository = userRepository;
        this.privacyService = privacyService;
        this.changeWriter = changeWriter;
    }

    public void mutualAdded(String userA, String userB) {
        events.publishEvent(buildSnapshot(userA, userB, ACTION_ADDED));
        events.publishEvent(buildSnapshot(userB, userA, ACTION_ADDED));
    }

    /** 双向删除：双方通讯录均为 removed（不再给对方推 peerDeletedMe 的 updated）。 */
    public void mutualDeleted(String userA, String userB) {
        events.publishEvent(buildRemoved(userA, userB));
        events.publishEvent(buildRemoved(userB, userA));
    }

    /**
     * @deprecated 用户删除已改为 {@link #mutualDeleted}；保留供旧调用/测试。
     */
    @Deprecated
    public void oneWayDeleted(String ownerUserId, String peerUserId) {
        events.publishEvent(buildRemoved(ownerUserId, peerUserId));
        if (friendRepository.existsByUserIdAndFriendUserIdAndStatus(
            peerUserId, ownerUserId, UserFriend.STATUS_ACTIVE)) {
            events.publishEvent(buildSnapshot(peerUserId, ownerUserId, ACTION_UPDATED));
        }
    }

    /** 修改好友备注后，通知本人其他在线端同步通讯录展示。 */
    public void remarkUpdated(String ownerUserId, String peerUserId) {
        events.publishEvent(buildSnapshot(ownerUserId, peerUserId, ACTION_REMARK_UPDATED));
    }

    /** 好友修改昵称/头像等资料后，通知所有仍保留该好友的用户刷新通讯录展示。 */
    public void peerProfileUpdated(String peerUserId) {
        if (peerUserId == null || peerUserId.isBlank()) {
            return;
        }
        for (String ownerUserId : friendRepository.findOwnerUserIdsByFriendUserIdAndStatus(
            peerUserId, UserFriend.STATUS_ACTIVE)) {
            events.publishEvent(buildSnapshot(ownerUserId, peerUserId, ACTION_PROFILE_UPDATED));
        }
    }

    /** 在线时间可见性变更后，通知通讯录好友刷新 lastActiveAt（含清除为 null）。 */
    public void lastActiveVisibilityChanged(String peerUserId) {
        if (peerUserId == null || peerUserId.isBlank()) {
            return;
        }
        for (String ownerUserId : friendRepository.findOwnerUserIdsByFriendUserIdAndStatus(
            peerUserId, UserFriend.STATUS_ACTIVE)) {
            events.publishEvent(buildSnapshot(ownerUserId, peerUserId, ACTION_PROFILE_UPDATED));
        }
    }

    private FriendListChangedCommittedEvent buildRemoved(String ownerUserId, String peerUserId) {
        Map<String, Object> payload = FriendContactChangeWriter.snapshotPayload(
            null, null, null,
            false, false, false, false,
            null, null, ACTION_REMOVED);
        // Tombstone：deleted=true 让客户端能区分"已删" vs "未拉到"
        FriendContactChangeWriter.WriteResult result = changeWriter.write(
            ownerUserId, FriendContactChangeWriter.TYPE_CONTACT_DELETED, peerUserId, payload, true);
        long seq = result.seq();
        return new FriendListChangedCommittedEvent(
            ownerUserId,
            ACTION_REMOVED,
            peerUserId,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            null,
            null,
            seq);
    }

    private FriendListChangedCommittedEvent buildSnapshot(String ownerUserId, String peerUserId, String action) {
        UserFriend row = friendRepository.findByUserIdAndFriendUserId(ownerUserId, peerUserId).orElse(null);
        User peer = userRepository.findByUserId(peerUserId).orElse(null);
        boolean reverseActive = friendRepository.existsByUserIdAndFriendUserIdAndStatus(
            peerUserId, ownerUserId, UserFriend.STATUS_ACTIVE);
        boolean inMyFriendList = row != null && row.getStatus() == UserFriend.STATUS_ACTIVE;
        boolean isFriend = inMyFriendList && reverseActive;
        Instant addedAt = row == null
            ? null
            : (row.getAddedAt() != null ? row.getAddedAt() : row.getImAddTime());
        String remark = row == null || row.getRemark() == null ? "" : row.getRemark();
        String peerNickname = row != null ? row.getFriendNickname() : (peer != null ? peer.getNickname() : null);
        String peerAvatarUrl = row != null ? row.getFriendAvatarUrl() : (peer != null ? peer.getAvatarUrl() : null);
        Long lastActiveAt = peer == null ? null : privacyService.lastActiveAtEpochMillis(peer);
        LastActiveVisibility lastActiveVisibility =
            peer == null ? null : privacyService.lastActiveVisibilityOf(peer);
        String visibilityName = lastActiveVisibility == null ? null : lastActiveVisibility.name();

        String eventType = switch (action) {
            case ACTION_ADDED -> FriendContactChangeWriter.TYPE_CONTACT_CREATED;
            case ACTION_REMARK_UPDATED -> FriendContactChangeWriter.TYPE_CONTACT_REMARK_UPDATED;
            case ACTION_PROFILE_UPDATED -> FriendContactChangeWriter.TYPE_CONTACT_PROFILE_UPDATED;
            default -> FriendContactChangeWriter.TYPE_CONTACT_UPDATED;
        };
        Map<String, Object> payload = FriendContactChangeWriter.snapshotPayload(
            peerNickname, peerAvatarUrl, remark,
            inMyFriendList, isFriend, inMyFriendList && !reverseActive, isFriend,
            lastActiveAt, visibilityName, action);
        long seq = changeWriter.write(ownerUserId, eventType, peerUserId, payload, false).seq();

        return new FriendListChangedCommittedEvent(
            ownerUserId,
            action,
            peerUserId,
            peerNickname,
            peerAvatarUrl,
            remark,
            addedAt,
            inMyFriendList,
            isFriend,
            inMyFriendList && !reverseActive,
            isFriend,
            lastActiveAt,
            lastActiveVisibility,
            seq);
    }
}
