package com.chat99.server.realtime;

import com.chat99.server.realtime.FriendRequestRealtimeNotifier.FriendRequestCommittedEvent;
import com.chat99.server.user.FriendRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class FriendRequestRealtimePublisher {

    private final ApplicationEventPublisher events;

    public FriendRequestRealtimePublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void pendingCreated(FriendRequest req) {
        publish(FriendRequestRealtimeNotifier.EVENT_RECEIVED, req,
            List.of(req.getToUserId()));
    }

    public void accepted(FriendRequest req) {
        publish(FriendRequestRealtimeNotifier.EVENT_ACCEPTED, req,
            List.of(req.getFromUserId()));
    }

    public void rejected(FriendRequest req) {
        publish(FriendRequestRealtimeNotifier.EVENT_REJECTED, req,
            List.of(req.getFromUserId()));
    }

    public void autoAccepted(FriendRequest req, String fromUserId, String toUserId) {
        publish(FriendRequestRealtimeNotifier.EVENT_AUTO_ACCEPTED, req,
            List.of(fromUserId, toUserId));
    }

    /** @deprecated 删后再加已不再直接恢复；客户端勿依赖 {@code friend_restored}。 */
    @Deprecated
    public void restored(String fromUserId, String toUserId, String addWording, String addSource, Instant createdAt) {
        events.publishEvent(new FriendRequestCommittedEvent(
            FriendRequestRealtimeNotifier.EVENT_RESTORED,
            fromUserId,
            toUserId,
            null,
            addWording,
            addSource,
            createdAt,
            List.of(fromUserId, toUserId)));
    }

    private void publish(String event, FriendRequest req, List<String> targets) {
        events.publishEvent(new FriendRequestCommittedEvent(
            event,
            req.getFromUserId(),
            req.getToUserId(),
            req.getId(),
            req.getAddWording(),
            req.getAddSource().name(),
            req.getCreatedAt(),
            targets));
    }
}
