package com.chat99.server.realtime;

import com.chat99.server.realtime.PresenceRealtimePublisher.PresenceChangedCommittedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class PresenceRealtimeNotifier {

    private static final Logger log = LoggerFactory.getLogger(PresenceRealtimeNotifier.class);

    private final RealtimeEventPublisher publisher;

    public PresenceRealtimeNotifier(RealtimeEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener
    public void onCommitted(PresenceChangedCommittedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", PresenceRealtimePublisher.EVENT);
        payload.put("peerUserId", event.peerUserId());
        payload.put("lastActiveAt", event.lastActiveAt());
        if (event.lastActiveVisibility() != null) {
            payload.put("lastActiveVisibility", event.lastActiveVisibility().name());
        }
        payload.put("online", event.online());
        boolean delivered = publisher.sendToUser(event.targetUserId(), payload);
        if (delivered) {
            log.debug("presence changed delivered viewer={} peer={}", event.targetUserId(), event.peerUserId());
        }
    }
}
