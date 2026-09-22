package com.chat99.server.realtime;

import java.util.Collection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class MomentRealtimePublisher {

    public static final String EVENT = "moment_changed";

    public record MomentChangedCommittedEvent(
        String action,
        String momentId,
        String authorUserId,
        Collection<String> targetUserIds,
        String actorUserId,
        Boolean liked,
        Long likeCount,
        String commentId,
        String replyToCommentId,
        Long commentCount) {}

    private final ApplicationEventPublisher events;

    public MomentRealtimePublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void publish(MomentChangedCommittedEvent event) {
        if (event == null || event.targetUserIds() == null || event.targetUserIds().isEmpty()) {
            return;
        }
        events.publishEvent(event);
    }
}
