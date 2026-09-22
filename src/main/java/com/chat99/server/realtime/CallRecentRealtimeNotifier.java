package com.chat99.server.realtime;

import com.chat99.server.call.CallRecentService.RecentItemView;
import com.chat99.server.realtime.CallRecentRealtimePublisher.CallRecentChangedCommittedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CallRecentRealtimeNotifier {

    private static final Logger log = LoggerFactory.getLogger(CallRecentRealtimeNotifier.class);

    private final RealtimeEventPublisher publisher;
    private final RealtimeProperties props;

    public CallRecentRealtimeNotifier(RealtimeEventPublisher publisher, RealtimeProperties props) {
        this.publisher = publisher;
        this.props = props;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(CallRecentChangedCommittedEvent event) {
        if (!props.enabled()) {
            return;
        }
        RecentItemView item = event.item();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", CallRecentRealtimePublisher.EVENT);
        payload.put("action", CallRecentRealtimePublisher.ACTION_ADDED);
        payload.put("callId", item.callId());
        if (item.callerUserId() != null) {
            payload.put("callerUserId", item.callerUserId());
        }
        if (item.operatorUserId() != null) {
            payload.put("operatorUserId", item.operatorUserId());
        }
        payload.put("peerUserId", item.peerUserId());
        if (item.peerName() != null) {
            payload.put("peerName", item.peerName());
        }
        if (item.peerAvatar() != null) {
            payload.put("peerAvatar", item.peerAvatar());
        }
        payload.put("mediaType", item.mediaType());
        payload.put("direction", item.direction());
        payload.put("result", item.result());
        payload.put("phase", "ENDED");
        payload.put("status", "ENDED");
        payload.put("durationSec", item.durationSec());
        payload.put("occurredAt", item.occurredAt());
        boolean delivered = publisher.sendToUser(event.targetUserId(), payload);
        log.debug("call recent realtime userId={} callId={} delivered={}", event.targetUserId(), item.callId(), delivered);
    }
}
