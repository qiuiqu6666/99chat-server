package com.chat99.server.user;

import com.chat99.server.common.PhoneUtils;
import org.springframework.stereotype.Service;

/**
 * 按已知 userId 查询公开资料（资料页/会话/群名片等）。
 * 与 {@link UserSearchService} 分离：不走搜人发现逻辑，也不计入搜人频控。
 */
@Service
public class UserProfileService {

    public record UserProfileView(
        String userId,
        String nickname,
        String avatarUrl,
        Integer avatarVersion,
        String phoneMasked,
        Long lastActiveAt,
        LastActiveVisibility lastActiveVisibility) {}

    private final UserPrivacyService privacyService;
    private final PhoneUtils phoneUtils;

    public UserProfileService(UserPrivacyService privacyService, PhoneUtils phoneUtils) {
        this.privacyService = privacyService;
        this.phoneUtils = phoneUtils;
    }

    public UserProfileView getProfile(String viewerUserId, String targetUserId) {
        User target = privacyService.requireActiveUser(targetUserId);
        return new UserProfileView(
            target.getUserId(),
            target.getNickname(),
            target.getAvatarUrl(),
            target.getAvatarVersion(),
            phoneUtils.mask(target.getPhone()),
            privacyService.visibleLastActiveAt(target, viewerUserId),
            privacyService.lastActiveVisibilityOf(target));
    }
}
