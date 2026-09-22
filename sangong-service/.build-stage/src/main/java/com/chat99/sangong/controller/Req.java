package com.chat99.sangong.controller;

import java.util.LinkedHashMap;
import java.util.Map;

/** 请求体取值工具（body 为 Map，兼容 PHP request->input 语义）。 */
final class Req {
    private Req() {}

    static Map<String, Object> body(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : body;
    }

    static String str(Map<String, Object> body, String key, String def) {
        Object v = body(body).get(key);
        return v == null ? def : String.valueOf(v);
    }

    static long lng(Map<String, Object> body, String key, long def) {
        Object v = body(body).get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static int intval(Map<String, Object> body, String key, int def) {
        return (int) lng(body, key, def);
    }

    static boolean has(Map<String, Object> body, String key) {
        return body != null && body.containsKey(key);
    }

    static Long lngOrNull(Map<String, Object> body, String key) {
        if (!has(body, key)) return null;
        long v = lng(body, key, 0);
        return v <= 0 ? null : v;
    }
}
