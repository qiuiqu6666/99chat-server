package com.chat99.server.realtime;

import com.chat99.server.user.PresenceService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TCP {@code presence_last_seen} 请求校验与参数归一化（供 Handler / 单测复用）。
 */
final class RealtimePresenceQuerySupport {

    static final int MAX_INFLIGHT = 3;

    private RealtimePresenceQuerySupport() {}

    record ParsedQuery(String requestId, List<String> userIds) {}

    record ParseError(String code) {}

    /** @return ParsedQuery 或 ParseError（二者其一） */
    static Object parse(Map<String, Object> body, int maxBatchSize) {
        String requestId = stringVal(body.get("requestId"));
        if (requestId == null) {
            return new ParseError("INVALID_INPUT");
        }
        Object raw = body.get("userIds");
        if (!(raw instanceof Collection<?> col) || col.isEmpty()) {
            return new ParseError("INVALID_INPUT");
        }
        Set<String> dedup = new LinkedHashSet<>();
        for (Object item : col) {
            String id = stringVal(item);
            if (id != null) {
                dedup.add(id);
            }
        }
        if (dedup.isEmpty()) {
            return new ParseError("INVALID_INPUT");
        }
        if (dedup.size() > maxBatchSize) {
            return new ParseError("BATCH_TOO_LARGE");
        }
        return new ParsedQuery(requestId, List.copyOf(dedup));
    }

    static Map<String, Object> okPayload(String requestId, PresenceService.LastSeenSnapshot snapshot) {
        return Map.of(
            "type", "presence_last_seen_ok",
            "requestId", requestId,
            "lastSeen", snapshot.lastSeen(),
            "lastActiveVisibility", snapshot.lastActiveVisibility(),
            "ts", System.currentTimeMillis());
    }

    static Map<String, Object> failPayload(String requestId, String code) {
        if (requestId == null || requestId.isBlank()) {
            return Map.of(
                "type", "presence_last_seen_fail",
                "code", code,
                "ts", System.currentTimeMillis());
        }
        return Map.of(
            "type", "presence_last_seen_fail",
            "requestId", requestId,
            "code", code,
            "ts", System.currentTimeMillis());
    }

    private static String stringVal(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
