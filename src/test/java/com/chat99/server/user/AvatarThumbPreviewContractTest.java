package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.chat99.server.common.PhoneUtils;
import com.chat99.server.group.GroupAvatarService;
import com.chat99.server.im.ImAdminClient;
import com.chat99.server.im.ImUserIdService;
import com.chat99.server.oss.OssProperties;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class AvatarThumbPreviewContractTest {

    @Mock
    UserPrivacyService privacyService;
    @Mock
    PhoneUtils phoneUtils;
    @Mock
    UserRepository userRepository;
    @Mock
    GroupAvatarService groupAvatarService;
    @Mock
    ImAdminClient imAdminClient;
    @Mock
    ImUserIdService imUserIdService;
    @Mock
    OssProperties ossProperties;
    @Mock
    UserFriendService userFriendService;

    @InjectMocks
    UserProfileService userProfileService;
    @InjectMocks
    UserAvatarService userAvatarService;

    private static final String THUMB_URL =
        "https://99chat.oss-cn-hongkong.aliyuncs.com/user-avatar/u1/123_thumb.jpg";
    private static final String PREVIEW_URL =
        "https://99chat.oss-cn-hongkong.aliyuncs.com/user-avatar/u1/123_preview.jpg";

    @Test
    void getProfile_returnsThumbUrlNotPreview() {
        User u = activeUserWithAvatar("u1", THUMB_URL, PREVIEW_URL, 5);

        when(privacyService.requireActiveUser("u1")).thenReturn(u);
        when(privacyService.visibleLastActiveAt(u, "viewer")).thenReturn(null);
        when(privacyService.lastActiveVisibilityOf(u)).thenReturn(LastActiveVisibility.everyone);
        when(phoneUtils.mask(null)).thenReturn("");

        UserProfileService.UserProfileView view = userProfileService.getProfile("viewer", "u1");

        assertThat(view.avatarUrl()).isEqualTo(THUMB_URL);
        assertThat(view.avatarUrl()).isNotEqualTo(PREVIEW_URL);
        assertThat(view.avatarVersion()).isEqualTo(5);
    }

    @Test
    void getProfile_defaultAvatar_returnsSameUrlForThumbAndPreview() {
        String defaultAvatar = "https://99chat.oss-cn-hongkong.aliyuncs.com/moren/default.png";
        User u = activeUserWithAvatar("u2", defaultAvatar, null, 0);

        when(privacyService.requireActiveUser("u2")).thenReturn(u);
        when(privacyService.visibleLastActiveAt(u, "viewer")).thenReturn(null);
        when(privacyService.lastActiveVisibilityOf(u)).thenReturn(LastActiveVisibility.everyone);
        when(phoneUtils.mask(null)).thenReturn("");

        UserProfileService.UserProfileView view = userProfileService.getProfile("viewer", "u2");

        assertThat(view.avatarUrl()).isEqualTo(defaultAvatar);
        assertThat(view.avatarVersion()).isEqualTo(0);
    }

    @Test
    void uploadAvatar_responseIncludesAvatarVersion() throws Exception {
        User user = activeUserWithAvatar("u3", "https://cdn/thumb_old.jpg", "https://cdn/preview_old.jpg", 7);
        MockMultipartFile file = new MockMultipartFile("file", "ava.jpg", "image/jpeg", new byte[] {1,2,3});

        when(userRepository.findByUserId("u3")).thenReturn(Optional.of(user));
        when(ossProperties.userAvatarPrefix()).thenReturn("user-avatar/");
        when(groupAvatarService.uploadAvatar(eq(file), org.mockito.ArgumentMatchers.anyString())).thenReturn(
            new GroupAvatarService.UploadResult("https://cdn/origin.jpg", "https://cdn/preview.jpg", "https://cdn/thumb.jpg"));
        when(imUserIdService.toIm("u3")).thenReturn("im_u3");

        UserAvatarService.AvatarUpdateResult result = userAvatarService.updateAvatar("u3", file);

        assertThat(result.avatarUrl()).isEqualTo("https://cdn/thumb.jpg");
        assertThat(result.thumbUrl()).isEqualTo("https://cdn/thumb.jpg");
        assertThat(result.previewUrl()).isEqualTo("https://cdn/preview.jpg");
        assertThat(result.avatarVersion()).isEqualTo(8);
    }

    private static User activeUserWithAvatar(String userId, String thumbUrl, String previewUrl, int version) {
        User u = new User();
        u.setUserId(userId);
        u.setNickname("Test");
        u.setAvatarUrl(thumbUrl);
        u.setAvatarPreviewUrl(previewUrl);
        u.setAvatarVersion(version);
        u.setStatus(1);
        u.setLastActiveAt(Instant.now());
        u.setLastActiveVisibility(LastActiveVisibility.everyone);
        return u;
    }
}
