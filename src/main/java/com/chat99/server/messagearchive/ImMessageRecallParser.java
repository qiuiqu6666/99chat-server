package com.chat99.server.messagearchive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ImMessageRecallParser {

    public static final String CMD_C2C_RECALL = "C2C.CallbackAfterMsgWithDraw";
    public static final String CMD_GROUP_RECALL = "Group.CallbackAfterRecallMsg";
    public static final String REVOKED_PREVIEW_TEXT = "[消息已撤回]";

    public static String groupMsgKey(String groupId, long msgSeq) {
        return groupId + ":" + msgSeq;
    }

    private final ObjectMapper json;

    public ImMessageRecallParser(ObjectMapper json) {
        this.json = json;
    }

    public boolean isRecallCommand(String command) {
        return CMD_C2C_RECALL.equals(command) || CMD_GROUP_RECALL.equals(command);
    }

    public Optional<ImMessageRecallEvent> parse(String rawBody, String command) {
        if (rawBody == null || rawBody.isBlank() || command == null || !isRecallCommand(command)) {
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

        long eventTimeMs = eventTimeMs(body);
        if (CMD_C2C_RECALL.equals(command)) {
            String msgKey = str(body.get("MsgKey"));
            if (msgKey == null || msgKey.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new ImMessageRecallEvent(command, List.of(msgKey.trim()), eventTimeMs));
        }

        String groupId = str(body.get("GroupId"));
        if (groupId == null || groupId.isBlank()) {
            return Optional.empty();
        }
        List<String> msgKeys = groupMsgKeys(groupId.trim(), body.get("MsgSeqList"));
        if (msgKeys.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ImMessageRecallEvent(command, msgKeys, eventTimeMs));
    }

    private static List<String> groupMsgKeys(String groupId, Object rawList) {
        if (!(rawList instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> msgKeys = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            long msgSeq = longVal(entry.get("MsgSeq"));
            if (msgSeq <= 0) {
                continue;
            }
            msgKeys.add(groupMsgKey(groupId, msgSeq));
        }
        return msgKeys;
    }

    private static long eventTimeMs(Map<String, Object> body) {
        long eventTime = longVal(body.get("EventTime"));
        if (eventTime <= 0) {
            return System.currentTimeMillis();
        }
        if (eventTime > 1_000_000_000_000L) {
            return eventTime;
        }
        return eventTime * 1000L;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
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
}
