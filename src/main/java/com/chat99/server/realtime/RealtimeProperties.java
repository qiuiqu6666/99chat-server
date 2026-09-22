/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="chat99.realtime")
public record RealtimeProperties(boolean enabled, int tcpPort, int authTimeoutSeconds, int idleTimeoutSeconds, int maxFrameLength, int maxConnections, int maxConnectionsPerIp, boolean redisBroadcastEnabled, boolean offlinePushEnabled, boolean friendListOfflinePushEnabled, boolean groupOfflinePushEnabled) {
    public RealtimeProperties {
        if (tcpPort <= 0) {
            tcpPort = 8082;
        }
        if (authTimeoutSeconds <= 0) {
            authTimeoutSeconds = 10;
        }
        if (idleTimeoutSeconds <= 0) {
            idleTimeoutSeconds = 90;
        }
        if (maxFrameLength <= 0) {
            maxFrameLength = 8192;
        }
        if (maxConnections <= 0) {
            maxConnections = 20000;
        }
        if (maxConnectionsPerIp <= 0) {
            maxConnectionsPerIp = 100;
        }
    }
}
