package com.chat99.server.kefu;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 把 Chatwoot 响应里的资源绝对地址改写成主服务入口 {@code /kefu}。
 * 必须覆盖 FRONTEND_URL 以及 Active Storage 302 用的 {@code http://127.0.0.1:3000}。
 */
final class KefuUrlRewriter {

    private KefuUrlRewriter() {
    }

    static String stripSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    static List<String> upstreamOrigins(String serviceUrl, String upstreamPublicUrl) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        addOrigin(origins, serviceUrl);
        addOrigin(origins, upstreamPublicUrl);
        addOrigin(origins, "http://127.0.0.1:3000");
        addOrigin(origins, "http://localhost:3000");
        addOrigin(origins, "http://[::1]:3000");
        addOrigin(origins, "http://127.0.0.1");
        addOrigin(origins, "http://localhost");
        return new ArrayList<>(origins);
    }

    static String rewrite(String body, String upstreamPublicUrl, String publicBase) {
        return rewrite(body, publicBase, List.of(stripSlash(upstreamPublicUrl)));
    }

    static String rewrite(String body, String publicBase, List<String> fromOrigins) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String to = stripSlash(publicBase);
        if (to.isEmpty()) {
            return body;
        }
        String out = body;
        for (String from : fromOrigins) {
            from = stripSlash(from);
            if (from.isEmpty() || from.equals(to)) {
                continue;
            }
            out = replaceOrigin(out, from, to);
            if (from.startsWith("http://")) {
                out = replaceOrigin(out, "ws://" + from.substring("http://".length()), wsOrigin(to));
            } else if (from.startsWith("https://")) {
                out = replaceOrigin(out, "wss://" + from.substring("https://".length()), wsOrigin(to));
            }
        }
        return out;
    }

    static String publicBaseFrom(HttpServletRequest request, String configuredPublicBase) {
        String configured = stripSlash(configuredPublicBase);
        if (!configured.isEmpty()) {
            return configured;
        }
        String proto = firstHop(request.getHeader("X-Forwarded-Proto"));
        if (proto == null || proto.isEmpty()) {
            proto = request.getScheme();
        }
        String host = firstHop(request.getHeader("X-Forwarded-Host"));
        if (host == null || host.isEmpty()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isEmpty()) {
            int port = request.getServerPort();
            boolean defaultPort = ("http".equalsIgnoreCase(proto) && port == 80)
                || ("https".equalsIgnoreCase(proto) && port == 443)
                || port < 0;
            host = request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        return proto + "://" + host + "/kefu";
    }

    private static void addOrigin(LinkedHashSet<String> origins, String url) {
        String from = stripSlash(url);
        if (!from.isEmpty()) {
            origins.add(from);
        }
    }

    private static String wsOrigin(String httpOrigin) {
        if (httpOrigin.startsWith("https://")) {
            return "wss://" + httpOrigin.substring("https://".length());
        }
        if (httpOrigin.startsWith("http://")) {
            return "ws://" + httpOrigin.substring("http://".length());
        }
        return httpOrigin;
    }

    private static String replaceOrigin(String body, String from, String to) {
        String out = body.replace(from + "/", to + "/");
        return out.replace("\"" + from + "\"", "\"" + to + "\"");
    }

    private static String firstHop(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        int comma = header.indexOf(',');
        return (comma < 0 ? header : header.substring(0, comma)).trim();
    }
}
