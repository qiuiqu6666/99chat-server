package com.chat99.server.realtime;

import io.netty.channel.Channel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RealtimeSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSessionRegistry.class);

    private final Map<String, Set<Channel>> byUserId = new ConcurrentHashMap<>();
    private final Map<Channel, String> channelUser = new ConcurrentHashMap<>();
    private final RealtimeOnlineStore onlineStore;

    public RealtimeSessionRegistry(RealtimeOnlineStore onlineStore) {
        this.onlineStore = onlineStore;
    }

    public void register(String userId, Channel channel) {
        byUserId.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(channel);
        channelUser.put(channel, userId);
        onlineStore.connect(userId, channel.id().asShortText());
        log.debug("realtime session registered userId={} channel={}", userId, channel.id());
    }

    public void unregister(Channel channel) {
        String userId = channelUser.remove(channel);
        if (userId == null) {
            return;
        }
        onlineStore.disconnect(userId, channel.id().asShortText());
        Set<Channel> channels = byUserId.get(userId);
        if (channels != null) {
            channels.remove(channel);
            if (channels.isEmpty()) {
                byUserId.remove(userId, channels);
            }
        }
        log.debug("realtime session unregistered userId={} channel={}", userId, channel.id());
    }

    public int localDeliver(String userId, String line) {
        Set<Channel> channels = byUserId.get(userId);
        if (channels == null || channels.isEmpty()) {
            return 0;
        }
        int sent = 0;
        for (Channel channel : channels) {
            if (channel.isActive()) {
                channel.writeAndFlush(line);
                sent++;
            }
        }
        return sent;
    }

    public boolean hasLocalSession(String userId) {
        Set<Channel> channels = byUserId.get(userId);
        return channels != null && !channels.isEmpty();
    }
}
