/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.common;

import com.chat99.server.common.TrustedProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientContext {
    private final TrustedProxyProperties trustedProxyProperties;

    public ClientContext(TrustedProxyProperties trustedProxyProperties) {
        this.trustedProxyProperties = trustedProxyProperties;
    }

    public String version(HttpServletRequest req) {
        return this.trim(req.getHeader("X-Client-Version"), 32);
    }

    public String platform(HttpServletRequest req) {
        return this.trim(req.getHeader("X-Client-Platform"), 16);
    }

    public String userAgent(HttpServletRequest req) {
        return this.trim(req.getHeader("User-Agent"), 255);
    }

    public String deviceModel(HttpServletRequest req) {
        return this.deviceModel(req, null);
    }

    public String deviceModel(HttpServletRequest req, String bodyValue) {
        if (bodyValue != null && !bodyValue.isBlank()) {
            return this.trim(bodyValue, 64);
        }
        return this.trim(req.getHeader("X-Device-Model"), 64);
    }

    public String ip(HttpServletRequest req) {
        String direct = req.getRemoteAddr();
        int hops = this.trustedProxyProperties.hops();
        if (hops <= 0 || !this.trustedProxyProperties.isTrustedProxy(direct)) {
            return direct;
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String candidate;
            String[] parts = xff.split(",");
            int idx = parts.length - hops;
            if (idx >= 0 && idx < parts.length && !(candidate = parts[idx].trim()).isBlank()) {
                return candidate;
            }
            return direct;
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return direct;
    }

    private String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
