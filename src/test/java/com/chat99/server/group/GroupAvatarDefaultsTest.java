package com.chat99.server.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroupAvatarDefaultsTest {

    private static final String DEFAULT_URL =
        "https://99chat.oss-cn-hongkong.aliyuncs.com/moren/qun.png";

    GroupAvatarDefaults defaults;

    @BeforeEach
    void setUp() {
        defaults = new GroupAvatarDefaults(new GroupProperties(DEFAULT_URL));
    }

    @Test
    void resolve_returnsDefaultWhenBlank() {
        assertThat(defaults.resolve(null)).isEqualTo(DEFAULT_URL);
        assertThat(defaults.resolve("")).isEqualTo(DEFAULT_URL);
        assertThat(defaults.resolve("   ")).isEqualTo(DEFAULT_URL);
    }

    @Test
    void resolve_normalizesHashInStoredUrl() {
        assertThat(defaults.resolve(
            "https://99chat.oss-cn-hongkong.aliyuncs.com/group-avatar/@TGS#256QJMM5CY/x_thumb.jpg"))
            .isEqualTo("https://99chat.oss-cn-hongkong.aliyuncs.com/group-avatar/@TGS%23256QJMM5CY/x_thumb.jpg");
    }

    @Test
    void applyToView_fillsMissingAvatar() {
        GroupProfileView view = new GroupProfileView(
            "@TGS#1", "Public", "test", "", null, null, 0, "", 1, 1, 400, null, null, 0L, null, null, null, false, "");
        GroupProfileView resolved = defaults.applyToView(view);
        assertThat(resolved.avatarUrl()).isEqualTo(DEFAULT_URL);
    }

    @Test
    void withMemberCount_setsMemberNum() {
        GroupProfileView view = new GroupProfileView(
            "@TGS#1", "Public", "test", "", null, null, 0, "", 1, 1, 400, null, null, 0L, null, null, null, false, "");
        GroupProfileView updated = view.withMemberCount(128);
        assertThat(updated.memberCount()).isEqualTo(128);
        assertThat(updated.memberNum()).isEqualTo(128);
    }
}
