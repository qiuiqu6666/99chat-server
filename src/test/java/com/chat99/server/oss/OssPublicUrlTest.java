package com.chat99.server.oss;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OssPublicUrlTest {

    @Test
    void encodeObjectKey_encodesHashInGroupId() {
        assertThat(OssPublicUrl.encodeObjectKey("group-avatar/@TGS#256QJMM5CY/x_thumb.jpg"))
            .isEqualTo("group-avatar/@TGS%23256QJMM5CY/x_thumb.jpg");
    }

    @Test
    void normalizePublicUrl_fixesUnencodedHash() {
        String raw = "https://99chat.oss-cn-hongkong.aliyuncs.com/group-avatar/@TGS#256QJMM5CY/x_thumb.jpg";
        assertThat(OssPublicUrl.normalizePublicUrl(raw))
            .isEqualTo("https://99chat.oss-cn-hongkong.aliyuncs.com/group-avatar/@TGS%23256QJMM5CY/x_thumb.jpg");
    }

    @Test
    void normalizePublicUrl_leavesAlreadyEncoded() {
        String encoded = "https://99chat.oss-cn-hongkong.aliyuncs.com/group-avatar/@TGS%23256QJMM5CY/x_thumb.jpg";
        assertThat(OssPublicUrl.normalizePublicUrl(encoded)).isEqualTo(encoded);
    }
}
