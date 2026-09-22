/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.common;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="chat99.security")
public record TrustedProxyProperties(Integer trustedProxyCount, String trustedProxyAddresses) {
    public int hops() {
        if (this.trustedProxyCount == null || this.trustedProxyCount < 0) {
            return 1;
        }
        return this.trustedProxyCount;
    }

    public boolean isTrustedProxy(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        return this.addresses().contains(TrustedProxyProperties.normalize(remoteAddress));
    }

    private Set<String> addresses() {
        String configured = this.trustedProxyAddresses;
        if (configured == null || configured.isBlank()) {
            configured = "127.0.0.1,::1,0:0:0:0:0:0:0:1";
        }
        return Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(TrustedProxyProperties::normalize).collect(Collectors.toUnmodifiableSet());
    }

    private static String normalize(String address) {
        String normalized = address.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (normalized.startsWith("::ffff:")) {
            normalized = normalized.substring("::ffff:".length());
        }
        return normalized;
    }
}
