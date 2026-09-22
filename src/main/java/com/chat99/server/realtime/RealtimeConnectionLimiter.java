/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.realtime;

import com.chat99.server.realtime.RealtimeProperties;
import io.netty.channel.socket.SocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class RealtimeConnectionLimiter {
    private final RealtimeProperties properties;
    private final AtomicInteger total = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> byIp = new ConcurrentHashMap();

    public RealtimeConnectionLimiter(RealtimeProperties properties) {
        this.properties = properties;
    }

    public boolean tryAcquire(SocketChannel channel) {
        String ip = RealtimeConnectionLimiter.remoteIp(channel);
        AtomicInteger ipCount = this.byIp.computeIfAbsent(ip, ignored -> new AtomicInteger());
        int currentTotal = this.total.incrementAndGet();
        int currentIp = ipCount.incrementAndGet();
        if (currentTotal > this.properties.maxConnections() || currentIp > this.properties.maxConnectionsPerIp()) {
            this.release(ip, ipCount);
            return false;
        }
        AtomicBoolean released = new AtomicBoolean();
        channel.closeFuture().addListener(ignored -> {
            if (released.compareAndSet(false, true)) {
                this.release(ip, ipCount);
            }
        });
        return true;
    }

    private void release(String ip, AtomicInteger ipCount) {
        this.total.updateAndGet(value -> Math.max(0, value - 1));
        if (ipCount.updateAndGet(value -> Math.max(0, value - 1)) == 0) {
            this.byIp.remove(ip, ipCount);
        }
    }

    private static String remoteIp(SocketChannel channel) {
        InetSocketAddress address = channel.remoteAddress();
        if (address != null && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
