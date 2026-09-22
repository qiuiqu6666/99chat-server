package com.chat99.server.realtime;

import com.chat99.server.realtime.MomentRealtimePublisher.MomentChangedCommittedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MomentRealtimeNotifier {

    private final RealtimeEventPublisher publisher;

    public MomentRealtimeNotifier(RealtimeEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(MomentChangedCommittedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", MomentRealtimePublisher.EVENT);
        payload.put("action", event.action());
        payload.put("momentId", event.momentId());
        payload.put("authorUserId", event.authorUserId());
        if (event.actorUserId() != null) {
            payload.put("actorUserId", event.actorUserId());
        }
        if (event.liked() != null) {
            payload.put("liked", event.liked());
        }
        if (event.likeCount() != null) {
            payload.put("likeCount", event.likeCount());
        }
        if (event.commentId() != null) {
            payload.put("commentId", event.commentId());
        }
        if (event.replyToCommentId() != null) {
            payload.put("replyToCommentId", event.replyToCommentId());
        }
        if (event.commentCount() != null) {
            payload.put("commentCount", event.commentCount());
        }
        publisher.sendToUsers(event.targetUserIds(), payload);
    }
}
