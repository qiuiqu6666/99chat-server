package com.chat99.server.realtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class FriendRequestRealtimeNotifier {

    public static final String EVENT_RECEIVED = "friend_request_received";
    public static final String EVENT_ACCEPTED = "friend_request_accepted";
    public static final String EVENT_REJECTED = "friend_request_rejected";
    public static final String EVENT_AUTO_ACCEPTED = "friend_request_auto_accepted";
    public static final String EVENT_RESTORED = "friend_restored";

    public record FriendRequestCommittedEvent(
        String event,
        String fromUserId,
        String toUserId,
        Long requestId,
        String addWording,
        String addSource,
        Instant createdAt,
        List<String> targetUserIds) {}

    private final RealtimeEventPublisher publisher;
    private final FriendRequestOfflinePushService offlinePush;

    public FriendRequestRealtimeNotifier(RealtimeEventPublisher publisher,
                                         FriendRequestOfflinePushService offlinePush) {
        this.publisher = publisher;
        this.offlinePush = offlinePush;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(FriendRequestCommittedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event.event());
        payload.put("fromUserId", event.fromUserId());
        payload.put("toUserId", event.toUserId());
        if (event.requestId() != null) {
            payload.put("requestId", event.requestId());
        }
        if (event.addWording() != null) {
            payload.put("addWording", event.addWording());
        }
        if (event.addSource() != null) {
            payload.put("addSource", event.addSource());
        }
        if (event.createdAt() != null) {
            payload.put("createdAt", event.createdAt().toString());
        }
        for (String userId : event.targetUserIds()) {
            boolean delivered = publisher.sendToUser(userId, payload);
            if (!delivered) {
                offlinePush.sendIfTcpUnreachable(userId, event);
            }
        }
    }
}
