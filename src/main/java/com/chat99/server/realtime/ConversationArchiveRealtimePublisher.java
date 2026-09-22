package com.chat99.server.realtime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class ConversationArchiveRealtimePublisher {

    public static final String EVENT = "conversation_archive_changed";

    public record ConversationArchiveChangedCommittedEvent(
        String userId,
        String chatType,
        String peerId,
        boolean archived,
        Long archivedAt,
        long updatedAt,
        boolean batch) {}

    private final ApplicationEventPublisher events;

    public ConversationArchiveRealtimePublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void singleChanged(String userId,
                              String chatType,
                              String peerId,
                              boolean archived,
                              Long archivedAt,
                              long updatedAt) {
        events.publishEvent(new ConversationArchiveChangedCommittedEvent(
            userId, chatType, peerId, archived, archivedAt, updatedAt, false));
    }

    public void batchChanged(String userId, long updatedAt) {
        events.publishEvent(new ConversationArchiveChangedCommittedEvent(
            userId, null, null, false, null, updatedAt, true));
    }
}
