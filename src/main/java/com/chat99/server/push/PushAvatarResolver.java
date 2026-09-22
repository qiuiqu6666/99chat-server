package com.chat99.server.push;

import com.chat99.server.auth.AuthProperties;
import com.chat99.server.group.GroupAvatarDefaults;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.oss.OssPublicUrl;
import com.chat99.server.user.User;
import com.chat99.server.user.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PushAvatarResolver {

    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final UserRepository userRepository;
    private final GroupAvatarDefaults groupAvatarDefaults;
    private final AuthProperties authProperties;

    public PushAvatarResolver(ImAdminClient imAdmin,
                              ImUserIdService imUserIdService,
                              UserRepository userRepository,
                              GroupAvatarDefaults groupAvatarDefaults,
                              AuthProperties authProperties) {
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.userRepository = userRepository;
        this.groupAvatarDefaults = groupAvatarDefaults;
        this.authProperties = authProperties;
    }

    /** 单聊 / 主叫：优先 IM 头像，其次库内头像，最后使用可匿名访问的默认头像。 */
    public String resolveUserAvatarUrl(String userId) {
        if (userId == null || userId.isBlank()) {
            return publicUrl(authProperties.avatarUrl());
        }
        String imAccount = imUserIdService.toIm(userId);
        String imUrl = imAdmin.getPortraitImageUrls(List.of(imAccount)).get(imAccount);
        String dbUrl = userRepository.findByUserId(userId).map(User::getAvatarUrl).orElse(null);
        String resolved = firstPublicUrl(imUrl, dbUrl);
        return resolved != null ? resolved : publicUrl(authProperties.avatarUrl());
    }

    /** 群聊：群 FaceUrl。 */
    public String resolveGroupAvatarUrl(String groupId) {
        if (groupId == null || groupId.isBlank()) {
            return null;
        }
        return groupAvatarDefaults.resolve(
            imAdmin.getGroupBaseInfo(groupId)
                .map(ImAdminClient.GroupBaseInfo::faceUrl)
                .orElse(null));
    }

    private static String firstPublicUrl(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            String resolved = publicUrl(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static String publicUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String url = OssPublicUrl.normalizePublicUrl(candidate.trim());
        return url.startsWith("https://") || url.startsWith("http://") ? url : null;
    }
}
