package com.chat99.server.messagearchive;

import com.chat99.server.im.ImChatPushPreviewService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ImMessageArchiveParser {

    public static final String CMD_C2C_AFTER = "C2C.CallbackAfterSendMsg";
    public static final String CMD_GROUP_AFTER = "Group.CallbackAfterSendMsg";
    public static final String CHAT_TYPE_C2C = "c2c";
    public static final String CHAT_TYPE_GROUP = "group";

    private final ObjectMapper json;
    private final ImChatPushPreviewService pushPreviewService;

    public ImMessageArchiveParser(ObjectMapper json, ImChatPushPreviewService pushPreviewService) {
        this.json = json;
        this.pushPreviewService = pushPreviewService;
    }

    public boolean isAfterSendCommand(String command) {
        return CMD_C2C_AFTER.equals(command) || CMD_GROUP_AFTER.equals(command);
    }

    public Optional<ImMessageArchiveEvent> parse(String rawBody, String command, String sdkAppId) {
        if (rawBody == null || rawBody.isBlank() || command == null || !isAfterSendCommand(command)) {
            return Optional.empty();
        }
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(rawBody, Map.class);
            body = parsed;
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (intVal(body.get("OnlineOnlyFlag")) == 1) {
            return Optional.empty();
        }
        if (intVal(body.get("SendMsgResult")) != 0) {
            return Optional.empty();
        }
        String fromAccount = str(body.get("From_Account"));
        if (fromAccount == null || fromAccount.isBlank()) {
            return Optional.empty();
        }
        Object msgBodyRaw = body.get("MsgBody");
        if (!(msgBodyRaw instanceof List<?> msgBody) || msgBody.isEmpty()) {
            return Optional.empty();
        }

        String msgBodyJson;
        try {
            msgBodyJson = json.writeValueAsString(msgBody);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }

        String elemType = extractElemType(msgBody);
        String previewText = buildPreviewText(command, body, fromAccount, msgBody);
        if (previewText != null && previewText.length() > 512) {
            previewText = previewText.substring(0, 512);
        }
        long msgTimeMs = msgTimeMs(body);
        long receivedAtMs = System.currentTimeMillis();

        if (CMD_C2C_AFTER.equals(command)) {
            String toAccount = str(body.get("To_Account"));
            if (toAccount == null || toAccount.isBlank() || fromAccount.equals(toAccount)) {
                return Optional.empty();
            }
            String tencentMsgKey = str(body.get("MsgKey"));
            String tencentMsgId = str(body.get("MsgId"));
            String msgKey = firstNonBlank(tencentMsgKey, tencentMsgId);
            if (msgKey == null) {
                return Optional.empty();
            }
            return Optional.of(new ImMessageArchiveEvent(
                UUID.randomUUID().toString(),
                sdkAppId,
                command,
                msgKey,
                CHAT_TYPE_C2C,
                fromAccount,
                toAccount,
                null,
                null,
                msgTimeMs,
                elemType,
                previewText,
                msgBodyJson,
                rawBody,
                receivedAtMs,
                genuineMsgId(tencentMsgId, tencentMsgKey)));
        }

        String groupId = str(body.get("GroupId"));
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }
        Long msgSeq = longObj(body.get("MsgSeq"));
        String tencentMsgId = str(body.get("MsgId"));
        String tencentMsgKey = str(body.get("MsgKey"));
        String msgKey = firstNonBlank(
            msgSeq != null ? groupId + ":" + msgSeq : null,
            tencentMsgId,
            tencentMsgKey);
        if (msgKey == null) {
            return Optional.empty();
        }
        return Optional.of(new ImMessageArchiveEvent(
            UUID.randomUUID().toString(),
            sdkAppId,
            command,
            msgKey,
            CHAT_TYPE_GROUP,
            fromAccount,
            null,
            groupId,
            msgSeq,
            msgTimeMs,
            elemType,
            previewText,
            msgBodyJson,
            rawBody,
            receivedAtMs,
            genuineMsgId(tencentMsgId, tencentMsgKey)));
    }

    /** 仅接受腾讯真正的 MsgId；禁止用 MsgKey 冒充。 */
    static String genuineMsgId(String msgId, String msgKey) {
        if (msgId == null || msgId.isBlank()) {
            return null;
        }
        if (msgKey != null && msgId.equals(msgKey)) {
            return null;
        }
        return msgId.trim();
    }

    public String partitionKey(ImMessageArchiveEvent event) {
        if (CHAT_TYPE_GROUP.equals(event.chatType())) {
            return "group:" + event.groupId();
        }
        String a = event.fromAccount();
        String b = event.peerAccount();
        if (a.compareTo(b) <= 0) {
            return "c2c:" + a + ":" + b;
        }
        return "c2c:" + b + ":" + a;
    }

    private String buildPreviewText(String command, Map<String, Object> body,
                                    String fromAccount, List<?> msgBody) {
        String recipient = null;
        String groupName = null;
        if (CMD_C2C_AFTER.equals(command)) {
            recipient = str(body.get("To_Account"));
        } else {
            groupName = "群消息";
        }
        ImChatPushPreviewService.PreviewContext ctx = new ImChatPushPreviewService.PreviewContext(
            recipient,
            CMD_C2C_AFTER.equals(command) ? CHAT_TYPE_C2C : CHAT_TYPE_GROUP,
            fromAccount,
            fromAccount,
            groupName);
        return pushPreviewService.summarizeMsgBody(ctx, msgBody);
    }

    private static String extractElemType(List<?> msgBody) {
        Object first = msgBody.get(0);
        if (first instanceof Map<?, ?> m) {
            String type = str(m.get("MsgType"));
            return type == null ? "UNKNOWN" : type;
        }
        return "UNKNOWN";
    }

    private static long msgTimeMs(Map<String, Object> body) {
        long sec = longVal(body.get("MsgTime"));
        if (sec <= 0) {
            sec = longVal(body.get("EventTime"));
        }
        if (sec <= 0) {
            return System.currentTimeMillis();
        }
        if (sec > 1_000_000_000_000L) {
            return sec;
        }
        return sec * 1000L;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static int intVal(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static long longVal(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static Long longObj(Object value) {
        long v = longVal(value);
        return v == 0L ? null : v;
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
