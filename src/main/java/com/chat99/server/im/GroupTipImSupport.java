package com.chat99.server.im;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/**
 * 识别客户端自建群灰字 {@code businessID/customType/type = group_tip}。
 */
public final class GroupTipImSupport {

    public static final String BUSINESS_ID = "group_tip";

    private GroupTipImSupport() {}

    public static boolean isGroupTipData(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        String businessId = firstNonBlank(
            str(data.get("businessID")),
            str(data.get("businessId")),
            str(data.get("customType")),
            str(data.get("type")));
        return BUSINESS_ID.equalsIgnoreCase(businessId);
    }

    /**
     * 回调 MsgBody 是否为「纯 group_tip」：至少一条 tip Custom，且无其它业务 Elem。
     * 空 MsgBody 视为非纯 tip。
     */
    public static boolean isPureGroupTipMessage(Map<String, Object> callbackBody, ObjectMapper json) {
        if (callbackBody == null || callbackBody.isEmpty() || json == null) {
            return false;
        }
        Object raw = callbackBody.get("MsgBody");
        if (!(raw instanceof List<?> msgBody) || msgBody.isEmpty()) {
            return false;
        }
        boolean hasTip = false;
        for (Object item : msgBody) {
            if (!(item instanceof Map<?, ?> msg)) {
                return false;
            }
            if (!"TIMCustomElem".equals(str(msg.get("MsgType")))) {
                return false;
            }
            Object contentRaw = msg.get("MsgContent");
            if (!(contentRaw instanceof Map<?, ?> content)) {
                return false;
            }
            Map<String, Object> data = parseDataMap(str(content.get("Data")), json);
            if (!isGroupTipData(data)) {
                return false;
            }
            hasTip = true;
        }
        return hasTip;
    }

    private static Map<String, Object> parseDataMap(String data, ObjectMapper json) {
        if (data == null || data.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(data, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
