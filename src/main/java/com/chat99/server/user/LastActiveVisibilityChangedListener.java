package com.chat99.server.user;

import com.chat99.server.realtime.FriendListRealtimePublisher;
import com.chat99.server.realtime.PresenceRealtimePublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class LastActiveVisibilityChangedListener {

    private final PresenceRealtimePublisher presenceRealtimePublisher;
    private final FriendListRealtimePublisher friendListRealtimePublisher;

    public LastActiveVisibilityChangedListener(PresenceRealtimePublisher presenceRealtimePublisher,
                                               FriendListRealtimePublisher friendListRealtimePublisher) {
        this.presenceRealtimePublisher = presenceRealtimePublisher;
        this.friendListRealtimePublisher = friendListRealtimePublisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(OnlinePrivacyProtectionService.LastActiveVisibilityChangedEvent event) {
        presenceRealtimePublisher.lastActiveVisibilityChanged(
            event.userId(), event.previous(), event.current());
        friendListRealtimePublisher.lastActiveVisibilityChanged(event.userId());
    }
}
