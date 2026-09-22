package com.chat99.server.call;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CallAvCallImBodyBuilder {

    private CallAvCallImBodyBuilder() {}

    public static Map<String, Object> fromArchiveRow(ObjectMapper json, Map<String, Object> row) throws Exception {
        Object bodyRaw = row.get("msg_body_json");
        if (bodyRaw == null) {
            return null;
        }
        List<Map<String, Object>> msgBody = json.readValue(bodyRaw.toString(), new TypeReference<>() {});
        Map<String, Object> imBody = new LinkedHashMap<>();
        imBody.put("From_Account", str(row.get("from_account")));
        imBody.put("To_Account", str(row.get("peer_account")));
        Object msgTimeMs = row.get("msg_time_ms");
        if (msgTimeMs instanceof Number n) {
            imBody.put("EventTime", n.longValue());
        }
        imBody.put("MsgBody", msgBody);
        return imBody;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromRawBody(ObjectMapper json, String rawBody) throws Exception {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        return json.readValue(rawBody, Map.class);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
