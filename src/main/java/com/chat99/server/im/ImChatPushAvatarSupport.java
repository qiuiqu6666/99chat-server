package com.chat99.server.im;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 从 IM 发消息后回调中解析客户端 ext / CloudCustomData 里的 avatarUrl，
 * 供离线 Push（APNs mutable-content、极光 extras）展示头像。
 */
@Component
public class ImChatPushAvatarSupport {

    private final ObjectMapper json;

    public ImChatPushAvatarSupport(ObjectMapper json) {
        this.json = json;
    }

    /** 优先 ext，否则 fallback。 */
    public String resolveAvatarUrl(Map<String, Object> callbackBody,
                                   List<?> msgBody,
                                   String fallback) {
        String fromExt = publicUrl(extractAvatarUrl(callbackBody, msgBody));
        if (fromExt != null) {
            return fromExt;
        }
        return publicUrl(fallback);
    }

    public String extractAvatarUrl(Map<String, Object> callbackBody, List<?> msgBody) {
        String fromCloud = parseAvatarFromJsonString(str(callbackBody == null ? null : callbackBody.get("CloudCustomData")));
        if (fromCloud != null) {
            return fromCloud;
        }
        if (msgBody == null || msgBody.isEmpty()) {
            return null;
        }
        for (Object item : msgBody) {
            if (!(item instanceof Map<?, ?> msg)) {
                continue;
            }
            String elemCloud = parseAvatarFromJsonString(str(msg.get("CloudCustomData")));
            if (elemCloud != null) {
                return elemCloud;
            }
            Object contentRaw = msg.get("MsgContent");
            if (!(contentRaw instanceof Map<?, ?> content)) {
                continue;
            }
            String fromExt = parseAvatarFromJsonString(firstNonBlank(
                str(content.get("Ext")), str(content.get("Extension"))));
            if (fromExt != null) {
                return fromExt;
            }
            String fromData = parseAvatarFromJsonString(str(content.get("Data")));
            if (fromData != null) {
                return fromData;
            }
        }
        return null;
    }

    private static String publicUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("https://") || trimmed.startsWith("http://")
            ? trimmed
            : null;
    }

    private String parseAvatarFromJsonString(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = json.readValue(raw.trim(), new TypeReference<>() {});
            return readAvatarField(map);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readAvatarField(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        String direct = firstNonBlank(str(map.get("avatarUrl")), str(map.get("avatar_url")));
        if (direct != null) {
            return direct;
        }
        Object ext = map.get("ext");
        if (ext instanceof Map<?, ?> extMap) {
            @SuppressWarnings("unchecked")
            String nested = readAvatarField((Map<String, Object>) extMap);
            if (nested != null) {
                return nested;
            }
        }
        Object data = map.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            @SuppressWarnings("unchecked")
            String nested = readAvatarField((Map<String, Object>) dataMap);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
