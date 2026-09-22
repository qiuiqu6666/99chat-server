package com.chat99.server.realtime;

import com.chat99.server.realtime.ConversationArchiveRealtimePublisher.ConversationArchiveChangedCommittedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ConversationArchiveRealtimeNotifier {

    private final RealtimeEventPublisher publisher;

    public ConversationArchiveRealtimeNotifier(RealtimeEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(ConversationArchiveChangedCommittedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", ConversationArchiveRealtimePublisher.EVENT);
        payload.put("updatedAt", event.updatedAt());
        payload.put("ts", event.updatedAt());
        if (event.batch()) {
            payload.put("batch", true);
        } else {
            payload.put("chatType", event.chatType());
            payload.put("peerId", event.peerId());
            payload.put("archived", event.archived());
            if (event.archivedAt() != null) {
                payload.put("archivedAt", event.archivedAt());
            }
        }
        publisher.sendToUser(event.userId(), payload);
    }
}
