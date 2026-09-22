package com.chat99.server.lifepayment.yuanren;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 大猿人签名：参数字典升序 + urldecode(query) + &apikey= + MD5 大写。 */
public final class YuanrenSignSupport {

    private YuanrenSignSupport() {}

    public static String sign(Map<String, String> params, String apikey) {
        if (apikey == null || apikey.isBlank()) {
            throw new IllegalArgumentException("apikey required");
        }
        Map<String, String> sorted = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    continue;
                }
                if ("sign".equalsIgnoreCase(e.getKey())) {
                    continue;
                }
                if (e.getValue() == null) {
                    continue;
                }
                sorted.put(e.getKey(), e.getValue());
            }
        }
        StringBuilder raw = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                raw.append('&');
            }
            first = false;
            raw.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        String decoded = urlDecode(raw.toString());
        String signStr = decoded + "&apikey=" + apikey;
        return md5Upper(signStr);
    }

    public static Map<String, String> withSign(Map<String, String> params, String apikey) {
        Map<String, String> out = new LinkedHashMap<>();
        if (params != null) {
            out.putAll(params);
        }
        out.put("sign", sign(out, apikey));
        return out;
    }

    public static boolean verify(Map<String, String> params, String apikey) {
        if (params == null || apikey == null || apikey.isBlank()) {
            return false;
        }
        String theirs = params.get("sign");
        if (theirs == null || theirs.isBlank()) {
            return false;
        }
        String ours = sign(params, apikey);
        return ours.equalsIgnoreCase(theirs.trim());
    }

    public static String formBody(Map<String, String> params) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            parts.add(urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()));
        }
        return String.join("&", parts);
    }

    private static String urlEncode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String urlDecode(String v) {
        return URLDecoder.decode(v, StandardCharsets.UTF_8);
    }

    private static String md5Upper(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(String.format(Locale.ROOT, "%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("md5 failed", e);
        }
    }
}
