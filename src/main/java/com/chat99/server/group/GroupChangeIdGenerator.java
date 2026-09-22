package com.chat99.server.group;

import java.util.UUID;

public final class GroupChangeIdGenerator {

    private GroupChangeIdGenerator() {
    }

    public static String newChangeEventId() {
        return "ce_" + UUID.randomUUID().toString().replace("-", "");
    }
}
