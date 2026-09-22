package com.chat99.server.realtime;

import com.chat99.server.push.ConversationPinService.PinItemView;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class ConversationPinRealtimePublisher {

    public static final String EVENT = "conversation_pin_changed";

    public record ConversationPinChangedCommittedEvent(
        String userId,
        List<PinItemView> items,
        long updatedAt,
        boolean batch,
        String chatType,
        String peerId,
        Boolean pinned) {}

    private final ApplicationEventPublisher events;

    public ConversationPinRealtimePublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void singleChanged(String userId,
                              String chatType,
                              String peerId,
                              boolean pinned,
                              List<PinItemView> items,
                              long updatedAt) {
        events.publishEvent(new ConversationPinChangedCommittedEvent(
            userId, List.copyOf(items), updatedAt, false, chatType, peerId, pinned));
    }

    public void batchChanged(String userId, List<PinItemView> items, long updatedAt) {
        events.publishEvent(new ConversationPinChangedCommittedEvent(
            userId, List.copyOf(items), updatedAt, true, null, null, null));
    }
}
