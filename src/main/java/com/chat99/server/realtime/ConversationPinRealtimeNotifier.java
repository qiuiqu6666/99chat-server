package com.chat99.server.realtime;

import com.chat99.server.push.ConversationPinService.PinItemView;
import com.chat99.server.realtime.ConversationPinRealtimePublisher.ConversationPinChangedCommittedEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ConversationPinRealtimeNotifier {

    private final RealtimeEventPublisher publisher;

    public ConversationPinRealtimeNotifier(RealtimeEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(ConversationPinChangedCommittedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", ConversationPinRealtimePublisher.EVENT);
        payload.put("items", toItemMaps(event.items()));
        payload.put("updatedAt", event.updatedAt());
        payload.put("ts", event.updatedAt());
        if (event.batch()) {
            payload.put("batch", true);
        } else {
            if (event.chatType() != null) {
                payload.put("chatType", event.chatType());
            }
            if (event.peerId() != null) {
                payload.put("peerId", event.peerId());
            }
            if (event.pinned() != null) {
                payload.put("pinned", event.pinned());
            }
        }
        publisher.sendToUser(event.userId(), payload);
    }

    private static List<Map<String, Object>> toItemMaps(List<PinItemView> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (PinItemView item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chatType", item.chatType());
            row.put("peerId", item.peerId());
            row.put("pinnedAt", item.pinnedAt());
            row.put("updatedAt", item.updatedAt());
            out.add(row);
        }
        return out;
    }
}
