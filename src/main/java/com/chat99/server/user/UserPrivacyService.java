package com.chat99.server.user;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserPrivacyService {

    public record PrivacyView(
        boolean allowViaQrCode,
        boolean allowViaCard,
        boolean allowViaGroup,
        boolean allowViaPhone,
        boolean allowViaUid) {}

    public record AddFriendCheckResult(boolean allowed, String reason, boolean friendAddRequiresVerify) {}

    public enum AddFriendChannel {
        card, qr, group;

        public static AddFriendChannel parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
            try {
                return AddFriendChannel.valueOf(raw.trim().toLowerCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
            }
        }

        public boolean allowedOn(User user) {
            return switch (this) {
                case card -> user.isAllowViaCard();
                case qr -> user.isAllowViaQrCode();
                case group -> user.isAllowViaGroup();
            };
        }

        public String disabledReason() {
            return switch (this) {
                case card -> "ADD_FRIEND_VIA_CARD_DISABLED";
                case qr -> "ADD_FRIEND_VIA_QR_DISABLED";
                case group -> "ADD_FRIEND_VIA_GROUP_DISABLED";
            };
        }
    }

    private final UserRepository userRepository;
    private final UserFriendRepository friendRepository;
    private final UserBlockService blockService;

    public UserPrivacyService(UserRepository userRepository,
                              UserFriendRepository friendRepository,
                              UserBlockService blockService) {
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
        this.blockService = blockService;
    }

    public PrivacyView getPrivacyForUser(String targetUserId) {
        return toView(requireActiveUser(targetUserId));
    }

    public AddFriendCheckResult checkAddFriendBySource(String targetUserId, String addSourceRaw) {
        if (addSourceRaw == null || addSourceRaw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        String source = addSourceRaw.trim().toLowerCase();
        return switch (source) {
            case "card" -> checkAddFriend(targetUserId, "card");
            case "qr_code" -> checkAddFriend(targetUserId, "qr");
            case "group" -> checkAddFriend(targetUserId, "group");
            case "search", "nearby" -> {
                User target = requireActiveUser(targetUserId);
                yield checkResult(target, target.isAllowViaUid(), "ADD_FRIEND_VIA_UID_DISABLED");
            }
            case "phone" -> {
                User target = requireActiveUser(targetUserId);
                yield checkResult(target, target.isAllowViaPhone(), "ADD_FRIEND_VIA_PHONE_DISABLED");
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        };
    }

    public AddFriendCheckResult checkAddFriend(String targetUserId, String channelRaw) {
        AddFriendChannel channel = AddFriendChannel.parse(channelRaw);
        User target = requireActiveUser(targetUserId);
        if (channel.allowedOn(target)) {
            return checkResult(target, true, null);
        }
        return checkResult(target, false, channel.disabledReason());
    }

    public AddFriendCheckResult checkAddFriendForCaller(String callerUserId, String targetUserId, String channelRaw) {
        AddFriendChannel channel = AddFriendChannel.parse(channelRaw);
        User target = requireActiveUser(targetUserId);
        if (callerUserId != null && !callerUserId.isBlank()
            && blockService.isEitherBlocked(callerUserId, targetUserId)) {
            return checkResult(target, false, "USER_BLOCKED");
        }
        if (channel.allowedOn(target)) {
            return checkResult(target, true, null);
        }
        return checkResult(target, false, channel.disabledReason());
    }

    private AddFriendCheckResult checkResult(User target, boolean allowed, String reason) {
        return new AddFriendCheckResult(allowed, reason, target.isFriendAddRequiresVerify());
    }

    public PrivacyView toView(User u) {
        return new PrivacyView(
            u.isAllowViaQrCode(),
            u.isAllowViaCard(),
            u.isAllowViaGroup(),
            u.isAllowViaPhone(),
            u.isAllowViaUid());
    }

    /** 对查看方可见的最后活跃时间；不可见时返回 null（管理端等内部用途保留）。 */
    public Long visibleLastActiveAt(User target, String viewerUserId) {
        if (!canViewerSeeLastActive(target, viewerUserId)) {
            return null;
        }
        return lastActiveAtEpochMillis(target);
    }

    public Long visibleLastActiveAt(UserRepository.OnlinePresenceView target, String targetUserId,
                                    String viewerUserId) {
        if (!canViewerSeeLastActive(target.getLastActiveVisibility(), targetUserId, viewerUserId)) {
            return null;
        }
        return lastActiveAtEpochMillis(target);
    }

    /** API 对外返回的原始最后活跃毫秒时间戳；展示与否由客户端根据 lastActiveVisibility 决定。 */
    public Long lastActiveAtEpochMillis(User target) {
        if (target == null) {
            return null;
        }
        Instant ts = target.getLastActiveAt();
        return ts == null ? null : ts.toEpochMilli();
    }

    public Long lastActiveAtEpochMillis(UserRepository.OnlinePresenceView target) {
        if (target == null) {
            return null;
        }
        Instant ts = target.getLastActiveAt();
        return ts == null ? null : ts.toEpochMilli();
    }

    public boolean canViewerSeeLastActive(User target, String viewerUserId) {
        return canViewerSeeLastActive(target.getLastActiveVisibility(), target.getUserId(), viewerUserId);
    }

    public boolean canViewerSeeLastActive(LastActiveVisibility visibility, String targetUserId, String viewerUserId) {
        if (visibility == null || visibility == LastActiveVisibility.hidden) {
            return false;
        }
        if (visibility == LastActiveVisibility.everyone) {
            return true;
        }
        if (viewerUserId == null || viewerUserId.isBlank()) {
            return false;
        }
        if (viewerUserId.equals(targetUserId)) {
            return true;
        }
        return friendRepository.isMutualActive(viewerUserId, targetUserId);
    }

    public LastActiveVisibility lastActiveVisibilityOf(User user) {
        return user.getLastActiveVisibility() == null
            ? LastActiveVisibility.everyone
            : user.getLastActiveVisibility();
    }

    User requireActiveUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_INPUT");
        }
        return userRepository.findByUserId(userId)
            .filter(u -> u.getStatus() == 1)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }
}
