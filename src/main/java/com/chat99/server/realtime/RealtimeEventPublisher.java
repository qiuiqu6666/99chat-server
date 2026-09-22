package com.chat99.server.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RealtimeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimeEventPublisher.class);

    private final RealtimeSessionRegistry sessions;
    private final RealtimeRedisBroadcaster redisBroadcaster;
    private final RealtimeOnlineStore onlineStore;
    private final RealtimeProperties props;
    private final ObjectMapper json;

    public RealtimeEventPublisher(RealtimeSessionRegistry sessions,
                                  RealtimeRedisBroadcaster redisBroadcaster,
                                  RealtimeOnlineStore onlineStore,
                                  RealtimeProperties props,
                                  ObjectMapper json) {
        this.sessions = sessions;
        this.redisBroadcaster = redisBroadcaster;
        this.onlineStore = onlineStore;
        this.props = props;
        this.json = json;
    }

    /** @return true 表示目标用户当前有 TCP 长连接（本机或 Redis 记录的在线连接） */
    public boolean sendToUser(String userId, Map<String, Object> payload) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.putIfAbsent("type", "event");
        body.putIfAbsent("ts", System.currentTimeMillis());
        String line = toLine(body);
        if (line == null) {
            return false;
        }
        return deliver(userId, line);
    }

    public void sendToUsers(Iterable<String> userIds, Map<String, Object> payload) {
        for (String userId : userIds) {
            sendToUser(userId, payload);
        }
    }

    private boolean deliver(String userId, String line) {
        int local = sessions.localDeliver(userId, line);
        if (local > 0) {
            log.debug("realtime delivered userId={} localConnections={}", userId, local);
            return true;
        }
        if (props.redisBroadcastEnabled()) {
            redisBroadcaster.publish(userId, line);
        }
        boolean online = onlineStore.hasConnection(userId);
        if (online) {
            log.debug("realtime fanout userId={} onlineOnCluster=true", userId);
        }
        return online;
    }

    private String toLine(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body) + "\n";
        } catch (JsonProcessingException e) {
            log.warn("realtime serialize failed: {}", e.getMessage());
            return null;
        }
    }
}
