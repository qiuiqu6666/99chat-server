package com.chat99.server.call;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AvCallImSignalingParser {

    private AvCallImSignalingParser() {}

    public record ParsedEvent(
        String inviteId,
        String callerId,
        String calleeId,
        int callEnd,
        int callType,
        String cmd,
        int actionType,
        long eventTimeMs,
        String roomId
    ) {
        boolean terminal() {
            if (callEnd > 0) {
                return true;
            }
            // TUICallKit：1=邀请 2=接听 3=拒接 4=挂断（call_end 可能仍为 0）
            return actionType == 3 || actionType == 4 || actionType == 5;
        }
    }

    /** 接听信令（非终态），用于推算通话时长。 */
    public static boolean isAcceptSignal(ParsedEvent event) {
        if (event == null || event.terminal()) {
            return false;
        }
        if (event.actionType() == 2) {
            return true;
        }
        String cmd = event.cmd();
        if (cmd == null || cmd.isBlank()) {
            return false;
        }
        return switch (cmd.toLowerCase()) {
            case "accept", "video_accept", "audio_accept" -> true;
            default -> false;
        };
    }

    /** 来电邀请（非挂断/拒接/取消）。 */
    public static boolean isIncomingInvite(ParsedEvent event) {
        if (event == null) {
            return false;
        }
        return !event.terminal() && event.callEnd() == 0
            && event.callerId() != null && !event.callerId().isBlank()
            && event.calleeId() != null && !event.calleeId().isBlank()
            && !event.callerId().equals(event.calleeId());
    }

    public static Optional<ParsedEvent> tryParse(ObjectMapper json, Map<String, Object> imBody) {
        if (imBody == null) {
            return Optional.empty();
        }
        Object msgBodyRaw = imBody.get("MsgBody");
        if (!(msgBodyRaw instanceof List<?> msgList)) {
            return Optional.empty();
        }
        long eventTimeMs = eventTimeMs(imBody);
        for (Object item : msgList) {
            Optional<ParsedEvent> parsed = parseMsgItem(json, map(item), eventTimeMs, imBody);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    private static Optional<ParsedEvent> parseMsgItem(ObjectMapper json, Map<String, Object> msg,
                                                      long eventTimeMs, Map<String, Object> imBody) {
        if (msg == null) {
            return Optional.empty();
        }
        Map<String, Object> content = map(msg.get("MsgContent"));
        if (content == null) {
            return Optional.empty();
        }
        String dataStr = str(content.get("Data"));
        if (dataStr == null || !dataStr.contains("av_call")) {
            return Optional.empty();
        }
        try {
            Map<String, Object> envelope = parseJsonMap(json, dataStr);
            if (envelope == null) {
                return Optional.empty();
            }
            Map<String, Object> av = resolveAvPayload(json, envelope);
            if (av == null || !"av_call".equals(str(av.get("businessID")))) {
                return Optional.empty();
            }
            String callerId = firstNonBlank(
                str(envelope.get("inviter")),
                nestedStr(av, "data", "inviter"),
                imBody != null ? str(imBody.get("From_Account")) : null);
            String calleeId = firstNonBlank(
                resolveCallee(callerId, envelope.get("inviteeList")),
                resolveCallee(callerId, nestedList(av, "data", "userIDs")),
                imBody != null ? str(imBody.get("To_Account")) : null);
            callerId = CallUserIdNormalizer.normalize(callerId);
            calleeId = CallUserIdNormalizer.normalize(calleeId);
            String roomId = resolveRoomId(av);
            String inviteId = firstNonBlank(
                str(envelope.get("inviteID")),
                str(envelope.get("inviteId")),
                str(av.get("inviteID")),
                str(av.get("inviteId")),
                syntheticInviteId(roomId, callerId, calleeId));
            if (inviteId == null || inviteId.isBlank()
                || callerId == null || callerId.isBlank()
                || calleeId == null || calleeId.isBlank()) {
                return Optional.empty();
            }
            int actionType = intVal(envelope.get("actionType"));
            if (actionType == 0 && "av_call".equals(str(envelope.get("businessID")))) {
                actionType = 1;
            }
            return Optional.of(new ParsedEvent(
                inviteId,
                callerId,
                calleeId,
                intVal(av.get("call_end")),
                intVal(av.get("call_type")),
                nestedStr(av, "data", "cmd"),
                actionType,
                eventTimeMs,
                roomId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> resolveAvPayload(ObjectMapper json, Map<String, Object> envelope)
            throws JsonProcessingException {
        if ("av_call".equals(str(envelope.get("businessID")))) {
            return envelope;
        }
        return parseAvPayload(json, envelope.get("data"));
    }

    private static String resolveRoomId(Map<String, Object> av) {
        return firstNonBlank(
            stringify(av.get("room_id")),
            nestedStr(av, "data", "room_id"),
            nestedStr(av, "data", "str_room_id"));
    }

    private static String syntheticInviteId(String roomId, String callerId, String calleeId) {
        if (roomId == null || roomId.isBlank() || callerId == null || calleeId == null) {
            return null;
        }
        return "room_" + roomId + "_" + callerId + "_" + calleeId;
    }

    private static String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return String.valueOf(n.longValue());
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public static CallRecordResult toRecordResult(ParsedEvent event) {
        if (event.callEnd() > 0) {
            return switch (event.callEnd()) {
                case 1 -> CallRecordResult.CANCELED;
                case 2 -> CallRecordResult.REJECTED;
                case 3 -> CallRecordResult.MISSED;
                case 4 -> CallRecordResult.ANSWERED;
                case 5 -> mapHangup(event.cmd());
                default -> callEndBeyondLegacyCodes(event);
            };
        }
        if (event.actionType() == 3) {
            return CallRecordResult.REJECTED;
        }
        if (event.actionType() == 4 || event.actionType() == 5) {
            return CallRecordResult.MISSED;
        }
        return CallRecordResult.FAILED;
    }

    /** 结合会话状态解析挂断类终态结果（actionType=4/5 且 call_end=0）。 */
    public static CallRecordResult resolveHangupResult(ParsedEvent event, boolean accepted) {
        if (event.callEnd() > 0) {
            return toRecordResult(event);
        }
        if (event.actionType() == 4 || event.actionType() == 5) {
            return accepted ? CallRecordResult.ANSWERED : CallRecordResult.MISSED;
        }
        return toRecordResult(event);
    }

    public static String normalizeMediaType(int callType) {
        return callType == 2 ? "video" : "audio";
    }

    private static CallRecordResult callEndBeyondLegacyCodes(ParsedEvent event) {
        // 新版 TUICallKit：call_end > 5 常表示通话时长（秒），配合 hangup 信令
        if ("hangup".equalsIgnoreCase(event.cmd()) || event.callEnd() > 5) {
            return CallRecordResult.ANSWERED;
        }
        return CallRecordResult.FAILED;
    }

    private static CallRecordResult mapHangup(String cmd) {
        if (cmd == null) {
            return CallRecordResult.MISSED;
        }
        return switch (cmd.toLowerCase()) {
            case "accept", "video_accept", "audio_accept" -> CallRecordResult.ANSWERED;
            case "reject" -> CallRecordResult.REJECTED;
            case "cancel" -> CallRecordResult.CANCELED;
            default -> CallRecordResult.MISSED;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonMap(ObjectMapper json, String raw) throws JsonProcessingException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return json.readValue(raw.trim(), Map.class);
    }

    private static Map<String, Object> parseAvPayload(ObjectMapper json, Object dataField)
            throws JsonProcessingException {
        if (dataField instanceof Map<?, ?> m) {
            return map(m);
        }
        if (dataField instanceof String s) {
            return parseJsonMap(json, s);
        }
        return null;
    }

    private static long eventTimeMs(Map<String, Object> imBody) {
        long eventTime = longVal(imBody.get("EventTime"));
        if (eventTime > 1_000_000_000_000L) {
            return eventTime;
        }
        if (eventTime > 0) {
            return eventTime * 1000L;
        }
        long msgTime = longVal(imBody.get("MsgTime"));
        return msgTime > 0 ? msgTime * 1000L : System.currentTimeMillis();
    }

    private static String resolveCallee(String callerId, Object inviteeListRaw) {
        if (!(inviteeListRaw instanceof List<?> list)) {
            return null;
        }
        String normalizedCaller = CallUserIdNormalizer.normalize(callerId);
        for (Object item : list) {
            String account = str(item);
            if (account == null || account.isBlank()) {
                continue;
            }
            String normalizedAccount = CallUserIdNormalizer.normalize(account);
            if (!normalizedAccount.isBlank() && !normalizedAccount.equals(normalizedCaller)) {
                return normalizedAccount;
            }
        }
        if (list.size() == 1) {
            return CallUserIdNormalizer.normalize(str(list.get(0)));
        }
        return null;
    }

    private static Object nestedList(Map<String, Object> root, String key, String nestedKey) {
        Map<String, Object> nested = map(root.get(key));
        return nested == null ? null : nested.get(nestedKey);
    }

    private static String nestedStr(Map<String, Object> root, String key, String nestedKey) {
        Map<String, Object> nested = map(root.get(key));
        return nested == null ? null : str(nested.get(nestedKey));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longVal(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Long.parseLong(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
