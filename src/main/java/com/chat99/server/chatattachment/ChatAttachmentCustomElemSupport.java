package com.chat99.server.chatattachment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ChatAttachmentCustomElemSupport {

    public static final String TYPE = "chat.attachment";
    public static final String NATIVE_VIDEO_TYPE = "chat.native-video";
    public static final int VERSION = 1;
    public static final int MAX_DATA_BYTES = 9000;
    public static final int MAX_NAME_CHARS = 128;

    private ChatAttachmentCustomElemSupport() {}

    public record AttachmentMessage(
        String attachmentId,
        String referenceId,
        String kind,
        String name,
        Long sizeBytes,
        String mimeType,
        Long durationMs,
        Integer width,
        Integer height,
        String thumbnailAttachmentId,
        int version
    ) {}

    public record NativeVideoMessage(
        String clientOperationId,
        String attachmentId,
        String referenceId,
        int version
    ) {}

    public static List<AttachmentMessage> extract(Map<String, Object> body, ObjectMapper json) {
        if (body == null || json == null) {
            return List.of();
        }
        Object msgBodyRaw = body.get("MsgBody");
        if (!(msgBodyRaw instanceof List<?> msgBody)) {
            return List.of();
        }
        List<AttachmentMessage> out = new ArrayList<>();
        for (Object item : msgBody) {
            if (!(item instanceof Map<?, ?> msg)) {
                continue;
            }
            if (!"TIMCustomElem".equals(str(msg.get("MsgType")))) {
                continue;
            }
            Object contentRaw = msg.get("MsgContent");
            if (!(contentRaw instanceof Map<?, ?> content)) {
                continue;
            }
            Map<String, Object> data = parse(str(content.get("Data")), json);
            AttachmentMessage parsed = parseMessage(data);
            if (parsed != null) {
                out.add(parsed);
            }
        }
        return out;
    }

    public static NativeVideoMessage extractNativeVideo(Map<String, Object> body, ObjectMapper json) {
        if (body == null || json == null) {
            return null;
        }
        Object raw = body.get("CloudCustomData");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            for (var e : map.entrySet()) {
                if (e.getKey() != null) {
                    data.put(e.getKey().toString(), e.getValue());
                }
            }
            return parseNativeVideo(data);
        }
        return parseNativeVideo(parse(str(raw), json));
    }

    public static NativeVideoMessage parseNativeVideo(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        if (!NATIVE_VIDEO_TYPE.equals(str(data.get("type")))) {
            return null;
        }
        Integer version = asInt(data.get("version"));
        if (version == null || version != VERSION) {
            return null;
        }
        String clientOperationId = str(data.get("clientOperationId"));
        String attachmentId = str(data.get("attachmentId"));
        String referenceId = str(data.get("referenceId"));
        if (clientOperationId == null || clientOperationId.isBlank()
            || attachmentId == null || attachmentId.isBlank()
            || referenceId == null || referenceId.isBlank()) {
            return null;
        }
        return new NativeVideoMessage(clientOperationId, attachmentId, referenceId, version);
    }

    public static AttachmentMessage parseMessage(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        if (!TYPE.equals(str(data.get("type")))) {
            return null;
        }
        Integer version = asInt(data.get("version"));
        if (version == null || version != VERSION) {
            return null;
        }
        String attachmentId = str(data.get("attachmentId"));
        String referenceId = str(data.get("referenceId"));
        String kind = str(data.get("kind"));
        String name = str(data.get("name"));
        if (attachmentId == null || referenceId == null || kind == null || name == null) {
            return null;
        }
        if (name.length() > MAX_NAME_CHARS) {
            return null;
        }
        return new AttachmentMessage(
            attachmentId,
            referenceId,
            kind,
            name,
            asLong(data.get("sizeBytes")),
            str(data.get("mimeType")),
            asLong(data.get("durationMs")),
            asInt(data.get("width")),
            asInt(data.get("height")),
            str(data.get("thumbnailAttachmentId")),
            version);
    }

    public static String pushPreview(AttachmentMessage msg) {
        String kindLabel = switch (msg.kind() == null ? "" : msg.kind()) {
            case "image" -> "[图片]";
            case "audio" -> "[语音]";
            case "video" -> "[视频]";
            default -> "[文件]";
        };
        if (msg.name() == null || msg.name().isBlank()) {
            return kindLabel;
        }
        return kindLabel + " " + msg.name();
    }

    private static Map<String, Object> parse(String raw, ObjectMapper json) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
