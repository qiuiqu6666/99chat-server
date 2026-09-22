package com.chat99.server.push;

import java.util.LinkedHashMap;
import java.util.Map;

public record PushMessage(
    String title,
    String body,
    Map<String, String> data,
    String collapseId,
    String threadId
) {

    public PushMessage {
        data = data == null ? Map.of() : Map.copyOf(data);
        collapseId = blankToNull(collapseId);
        threadId = blankToNull(threadId);
    }

    public static PushMessage of(String title, String body) {
        return new PushMessage(title, body, Map.of(), null, null);
    }

    public PushMessage withData(String key, String value) {
        if (key == null || key.isBlank() || value == null) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(data);
        merged.put(key, value);
        return new PushMessage(title, body, merged, collapseId, threadId);
    }

    public PushMessage withDataMap(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(data);
        extra.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null) {
                merged.put(key, value);
            }
        });
        return new PushMessage(title, body, merged, collapseId, threadId);
    }

    /** iOS APNs：collapse-id（通常 msgKey）+ thread-id（按会话分组）。 */
    public PushMessage withApnsGrouping(String collapseId, String threadId) {
        return new PushMessage(title, body, data, collapseId, threadId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
