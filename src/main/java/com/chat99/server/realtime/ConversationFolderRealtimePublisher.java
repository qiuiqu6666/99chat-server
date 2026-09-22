package com.chat99.server.realtime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class ConversationFolderRealtimePublisher {

    public static final String EVENT = "conversation_folder_changed";

    public record ConversationFolderChangedCommittedEvent(
        String userId,
        String folderId,
        String action,
        long updatedAt,
        boolean batch) {}

    private final ApplicationEventPublisher events;

    public ConversationFolderRealtimePublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void singleChanged(String userId, String folderId, String action, long updatedAt) {
        events.publishEvent(new ConversationFolderChangedCommittedEvent(
            userId, folderId, action, updatedAt, false));
    }

    public void batchChanged(String userId, long updatedAt) {
        events.publishEvent(new ConversationFolderChangedCommittedEvent(
            userId, null, null, updatedAt, true));
    }
}
