package com.chat99.server.group;

import com.chat99.server.user.User;
import com.chat99.server.user.UserFriend;
import com.chat99.server.user.UserFriendRepository;
import com.chat99.server.user.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GroupMemberEnrichmentService {

    public record UserBrief(String nickname, String avatarUrl) {}

    private final UserRepository userRepository;
    private final UserFriendRepository friendRepository;

    public GroupMemberEnrichmentService(UserRepository userRepository,
                                        UserFriendRepository friendRepository) {
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
    }

    public Map<String, UserBrief> loadUserBriefs(Collection<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<User> users = userRepository.findByUserIdIn(userIds);
        Map<String, UserBrief> out = new HashMap<>();
        for (User user : users) {
            out.put(user.getUserId(), new UserBrief(user.getNickname(), user.getAvatarUrl()));
        }
        return out;
    }

    public Map<String, String> loadFriendRemarks(String viewerUserId, Collection<String> peerUserIds) {
        if (viewerUserId == null || peerUserIds == null || peerUserIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (UserFriend row : friendRepository.findByUserIdAndFriendUserIdInAndStatus(
            viewerUserId, peerUserIds, UserFriend.STATUS_ACTIVE)) {
            if (row.getRemark() != null && !row.getRemark().isBlank()) {
                out.put(row.getFriendUserId(), row.getRemark());
            }
        }
        return out;
    }
}
