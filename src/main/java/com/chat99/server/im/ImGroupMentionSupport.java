package com.chat99.server.im;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 解析腾讯云 Group.CallbackAfterSendMsg 的 GroupAtInfo。 */
final class ImGroupMentionSupport {

    private ImGroupMentionSupport() {
    }

    static GroupMentions parse(Object raw) {
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return GroupMentions.NONE;
        }
        boolean atAll = false;
        Set<String> userIds = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> info)) {
                continue;
            }
            if (intVal(info.get("GroupAtAllFlag")) == 1) {
                atAll = true;
                continue;
            }
            String userId = str(info.get("GroupAt_Account"));
            if (userId != null && !userId.isBlank()) {
                userIds.add(userId.trim());
            }
        }
        return new GroupMentions(atAll, Set.copyOf(userIds));
    }

    record GroupMentions(boolean atAll, Set<String> userIds) {
        private static final GroupMentions NONE = new GroupMentions(false, Set.of());

        boolean mentions(String userId) {
            return atAll || (userId != null && userIds.contains(userId.trim()));
        }
    }

    private static int intVal(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
