package com.chat99.server.realtime;

import com.chat99.server.user.LastActiveVisibility;
import com.chat99.server.user.User;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserPrivacyService;
import com.chat99.server.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class PresenceRealtimePublisher {

    public static final String EVENT = "presence_changed";

    public record PresenceChangedCommittedEvent(
        String targetUserId,
        String peerUserId,
        Long lastActiveAt,
        LastActiveVisibility lastActiveVisibility,
        boolean online) {}

    private final ApplicationEventPublisher events;
    private final UserRepository userRepository;
    private final UserFriendRepository friendRepository;
    private final UserPrivacyService privacyService;

    public PresenceRealtimePublisher(ApplicationEventPublisher events,
                                     UserRepository userRepository,
                                     UserFriendRepository friendRepository,
                                     UserPrivacyService privacyService) {
        this.events = events;
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
        this.privacyService = privacyService;
    }

    /** 用户活跃时间落库后，通知可查看其上线时间的好友刷新展示。 */
    public void userBecameActive(String activeUserId, Instant lastActiveAt) {
        if (activeUserId == null || activeUserId.isBlank() || lastActiveAt == null) {
            return;
        }
        User user = userRepository.findByUserId(activeUserId).orElse(null);
        if (user == null || user.getStatus() != 1) {
            return;
        }
        LastActiveVisibility visibility = privacyService.lastActiveVisibilityOf(user);
        List<String> viewers = friendRepository.findOwnerUserIdsByFriendUserIdAndStatus(
            activeUserId, UserFriend.STATUS_ACTIVE);
        if (viewers.isEmpty()) {
            return;
        }
        long epochMs = lastActiveAt.toEpochMilli();
        for (String viewerId : viewers) {
            events.publishEvent(new PresenceChangedCommittedEvent(
                viewerId, activeUserId, epochMs, visibility, true));
        }
    }

    /** 在线时间可见性变更后，通知曾可见/现可见的好友刷新或清除展示。 */
    public void lastActiveVisibilityChanged(String userId,
                                            LastActiveVisibility previous,
                                            LastActiveVisibility current) {
        if (userId == null || userId.isBlank() || previous == current) {
            return;
        }
        User user = userRepository.findByUserId(userId).orElse(null);
        if (user == null || user.getStatus() != 1) {
            return;
        }
        List<String> viewers = friendRepository.findOwnerUserIdsByFriendUserIdAndStatus(
            userId, UserFriend.STATUS_ACTIVE);
        if (viewers.isEmpty()) {
            return;
        }
        Long visibleNow = user.getLastActiveAt() == null ? null : user.getLastActiveAt().toEpochMilli();
        for (String viewerId : viewers) {
            events.publishEvent(new PresenceChangedCommittedEvent(
                viewerId, userId, visibleNow, current, false));
        }
    }
}
