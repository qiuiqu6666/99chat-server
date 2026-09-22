package com.chat99.server.user;

import com.chat99.server.group.GroupAvatarService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.oss.OssProperties;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserAvatarService {

    private final UserRepository userRepository;
    private final GroupAvatarService avatarService;
    private final ImAdminClient imAdmin;
    private final ImUserIdService imUserIdService;
    private final OssProperties ossProps;
    private final UserFriendService userFriendService;

    public UserAvatarService(UserRepository userRepository, GroupAvatarService avatarService,
                             ImAdminClient imAdmin, ImUserIdService imUserIdService,
                             OssProperties ossProps,
                             UserFriendService userFriendService) {
        this.userRepository = userRepository;
        this.avatarService = avatarService;
        this.imAdmin = imAdmin;
        this.imUserIdService = imUserIdService;
        this.ossProps = ossProps;
        this.userFriendService = userFriendService;
    }

    public record AvatarUpdateResult(
        String avatarUrl,
        String originUrl,
        String previewUrl,
        String thumbUrl,
        int avatarVersion) {}

    @Transactional
    public AvatarUpdateResult updateAvatar(String userId, MultipartFile file) throws IOException {
        User u = userRepository.findByUserId(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        if (u.getStatus() != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        }

        String base = ossProps.userAvatarPrefix() + userId + "/" + Instant.now().toEpochMilli()
            + "_" + UUID.randomUUID().toString().substring(0, 8);
        GroupAvatarService.UploadResult uploaded = avatarService.uploadAvatar(file, base);

        String thumbUrl = uploaded.thumbUrl();
        u.setAvatarUrl(thumbUrl);
        u.setAvatarPreviewUrl(uploaded.previewUrl());
        u.setAvatarVersion(u.getAvatarVersion() + 1);
        userRepository.save(u);
        imAdmin.profileUpdateFaceUrl(imUserIdService.toIm(userId), thumbUrl);
        userFriendService.onUserAvatarUpdated(userId, thumbUrl, uploaded.previewUrl());

        return new AvatarUpdateResult(thumbUrl, uploaded.originUrl(), uploaded.previewUrl(), uploaded.thumbUrl(),
            u.getAvatarVersion());
    }
}
