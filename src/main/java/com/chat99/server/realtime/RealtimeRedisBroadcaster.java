package com.chat99.server.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
public class RealtimeRedisBroadcaster implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeRedisBroadcaster.class);
    static final String CHANNEL = "realtime:fanout";

    private final StringRedisTemplate redis;
    private final RealtimeSessionRegistry sessions;
    private final RealtimeProperties props;
    private final ObjectMapper json;
    private final RedisMessageListenerContainer listenerContainer;

    public RealtimeRedisBroadcaster(StringRedisTemplate redis,
                                      RealtimeSessionRegistry sessions,
                                      RealtimeProperties props,
                                      ObjectMapper json,
                                      RedisMessageListenerContainer listenerContainer) {
        this.redis = redis;
        this.sessions = sessions;
        this.props = props;
        this.json = json;
        this.listenerContainer = listenerContainer;
    }

    @PostConstruct
    void subscribe() {
        if (!props.enabled() || !props.redisBroadcastEnabled()) {
            return;
        }
        listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL));
        log.info("realtime redis broadcaster subscribed channel={}", CHANNEL);
    }

    @PreDestroy
    void unsubscribe() {
        listenerContainer.removeMessageListener(this);
    }

    public void publish(String userId, String line) {
        if (!props.enabled() || !props.redisBroadcastEnabled()) {
            return;
        }
        try {
            String payload = json.writeValueAsString(Map.of("userId", userId, "line", line));
            redis.convertAndSend(CHANNEL, payload);
        } catch (Exception e) {
            log.warn("realtime redis publish failed userId={} err={}", userId, e.getMessage());
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            Map<String, String> envelope = json.readValue(message.getBody(), new TypeReference<>() {});
            String userId = envelope.get("userId");
            String line = envelope.get("line");
            if (userId == null || line == null) {
                return;
            }
            int sent = sessions.localDeliver(userId, line);
            if (sent > 0) {
                log.debug("realtime redis fanout userId={} localConnections={}", userId, sent);
            }
        } catch (Exception e) {
            log.warn("realtime redis fanout parse failed: {}", e.getMessage());
        }
    }
}
