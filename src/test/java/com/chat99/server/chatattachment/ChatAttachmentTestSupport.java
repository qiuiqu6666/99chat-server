package com.chat99.server.chatattachment;

import java.util.List;

final class ChatAttachmentTestSupport {

    private ChatAttachmentTestSupport() {}

    static ChatAttachmentProperties props() {
        return new ChatAttachmentProperties(
            1, false, false, true, false, 104_857_600L,
            new ChatAttachmentProperties.NativeMax(29_360_128L, 29_360_128L, 104_857_600L, 104_857_600L),
            2_147_483_648L, 21_474_836_480L, 10_737_418_240L, "Asia/Shanghai",
            3, 8_388_608L, 2, 86_400, 900, 900, 30, 259_200, 604_800, 604_800,
            1_048_576L, 1280, 32, List.of("android", "ios"), "3.0.1", 7, 1,
            false, null, "test-media-hmac-secret",
            new ChatAttachmentProperties.Oss(null, "chat-att-private", "ak", "sk", null),
            new ChatAttachmentProperties.Gray(List.of("u1"), List.of("g1"), true),
            new ChatAttachmentProperties.RateLimit(10, 30, 60, 20, 60),
            new ChatAttachmentProperties.Jobs(300_000, "0 20 4 * * ?", 600_000),
            null, 0);
    }
}
