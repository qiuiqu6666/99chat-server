package com.chat99.server.realtime;

import com.chat99.server.realtime.ConversationFolderRealtimePublisher.ConversationFolderChangedCommittedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ConversationFolderRealtimeNotifier {

    private final RealtimeEventPublisher publisher;

    public ConversationFolderRealtimeNotifier(RealtimeEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(ConversationFolderChangedCommittedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", ConversationFolderRealtimePublisher.EVENT);
        payload.put("updatedAt", event.updatedAt());
        payload.put("ts", event.updatedAt());
        if (event.batch()) {
            payload.put("batch", true);
        } else {
            payload.put("folderId", event.folderId());
            payload.put("action", event.action());
        }
        publisher.sendToUser(event.userId(), payload);
    }
}
