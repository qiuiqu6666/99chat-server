package com.chat99.server.realtime;

import com.chat99.server.call.CallRecentService;
import com.chat99.server.call.CallRecentService.RecentItemView;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class CallRecentRealtimePublisher {

    public static final String EVENT = "call_recent_changed";
    public static final String ACTION_ADDED = "added";

    public record CallRecentChangedCommittedEvent(String targetUserId, RecentItemView item) {}

    private final ApplicationEventPublisher events;
    private final CallRecentService recentService;

    public CallRecentRealtimePublisher(ApplicationEventPublisher events, CallRecentService recentService) {
        this.events = events;
        this.recentService = recentService;
    }

    /** 通话终态落库后，通知主叫与被叫刷新最近通话列表。 */
    public void callEnded(String callId, String callerId, String calleeId) {
        if (callId == null || callId.isBlank()) {
            return;
        }
        publishItem(callerId, callId);
        publishItem(calleeId, callId);
    }

    private void publishItem(String userId, String callId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        Optional<RecentItemView> item = recentService.findRecentItem(userId, callId);
        item.ifPresent(view -> events.publishEvent(new CallRecentChangedCommittedEvent(userId, view)));
    }
}
